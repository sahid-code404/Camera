#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* kTag = "CameraRawNative";
constexpr int kQ16 = 65536;
constexpr int kCfaPeriod = 2;
constexpr int kAlignRadius = 8;
constexpr int kAlignBorder = 20;
constexpr int kAlignStep = 28;
constexpr int kReferenceWeight = 4;

struct Mapping {
    int fd = -1;
    size_t bytes = 0;
    const uint16_t* pixels = nullptr;

    Mapping() = default;
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    Mapping(Mapping&& other) noexcept {
        fd = other.fd;
        bytes = other.bytes;
        pixels = other.pixels;
        other.fd = -1;
        other.bytes = 0;
        other.pixels = nullptr;
    }
    Mapping& operator=(Mapping&& other) noexcept {
        if (this != &other) {
            closeNow();
            fd = other.fd;
            bytes = other.bytes;
            pixels = other.pixels;
            other.fd = -1;
            other.bytes = 0;
            other.pixels = nullptr;
        }
        return *this;
    }
    ~Mapping() { closeNow(); }

    bool openReadOnly(const std::string& path, size_t expectedBytes) {
        fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) return false;
        struct stat st {};
        if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < expectedBytes) {
            closeNow();
            return false;
        }
        bytes = static_cast<size_t>(st.st_size);
        void* ptr = mmap(nullptr, bytes, PROT_READ, MAP_SHARED, fd, 0);
        if (ptr == MAP_FAILED) {
            pixels = nullptr;
            closeNow();
            return false;
        }
        pixels = static_cast<const uint16_t*>(ptr);
        return true;
    }

    void closeNow() {
        if (pixels != nullptr) {
            munmap(const_cast<uint16_t*>(pixels), bytes);
            pixels = nullptr;
        }
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
        bytes = 0;
    }
};

struct Frame {
    Mapping mapping;
    int64_t exposureNs = 1;
    int iso = 100;
    std::array<int, 4> black{0, 0, 0, 0};
    int scaleToReferenceQ16 = kQ16;
};

struct Alignment {
    int dx = 0;
    int dy = 0;
    int64_t meanError = 0;
    float confidence = 1.0f;
};

inline int cfaIndex(int x, int y) {
    return ((y & 1) << 1) | (x & 1);
}

inline int clampInt(int v, int lo, int hi) {
    return std::min(hi, std::max(lo, v));
}

inline int normalizedQ16(const Frame& frame, int raw, int x, int y, int whiteLevel) {
    const int black = frame.black[cfaIndex(x, y)];
    const int range = std::max(1, whiteLevel - black);
    const int signal = std::max(0, raw - black);
    return clampInt(static_cast<int>((static_cast<int64_t>(signal) * 65535 + range / 2) / range), 0, 65535);
}

inline int encodedFromQ16(const Frame& frame, int value, int x, int y, int whiteLevel) {
    const int black = frame.black[cfaIndex(x, y)];
    const int range = std::max(1, whiteLevel - black);
    const int signal = static_cast<int>((static_cast<int64_t>(clampInt(value, 0, 65535)) * range + 32767) / 65535);
    return clampInt(black + signal, 0, std::min(65535, whiteLevel));
}

inline int scaledToReference(int normalized, int scaleQ16) {
    return clampInt(static_cast<int>((static_cast<int64_t>(normalized) * scaleQ16 + 32768) >> 16), 0, 131070);
}

inline int even(int value) {
    return value & ~1;
}

int blockLuma(const Frame& frame, int x, int y, int width, int height, int whiteLevel) {
    const int x1 = std::min(width - 1, x + 1);
    const int y1 = std::min(height - 1, y + 1);
    const auto* p = frame.mapping.pixels;
    const int a = normalizedQ16(frame, p[y * width + x], x, y, whiteLevel);
    const int b = normalizedQ16(frame, p[y * width + x1], x1, y, whiteLevel);
    const int c = normalizedQ16(frame, p[y1 * width + x], x, y1, whiteLevel);
    const int d = normalizedQ16(frame, p[y1 * width + x1], x1, y1, whiteLevel);
    return (a + b + c + d) >> 2;
}

Alignment findAlignment(
    const Frame& reference,
    const Frame& candidate,
    int width,
    int height,
    int whiteLevel
) {
    int bestDx = 0;
    int bestDy = 0;
    int64_t bestScore = std::numeric_limits<int64_t>::max();
    int bestSamples = 0;

    for (int dy = -kAlignRadius; dy <= kAlignRadius; dy += kCfaPeriod) {
        for (int dx = -kAlignRadius; dx <= kAlignRadius; dx += kCfaPeriod) {
            int64_t error = 0;
            int samples = 0;
            for (int y = even(kAlignBorder); y < height - kAlignBorder; y += kAlignStep) {
                const int cy = y + dy;
                if (cy < 0 || cy + 1 >= height) continue;
                for (int x = even(kAlignBorder); x < width - kAlignBorder; x += kAlignStep) {
                    const int cx = x + dx;
                    if (cx < 0 || cx + 1 >= width) continue;
                    const int ref = blockLuma(reference, x, y, width, height, whiteLevel);
                    if (ref < 900 || ref > 64500) continue;
                    const int src = blockLuma(candidate, cx, cy, width, height, whiteLevel);
                    const int scaled = scaledToReference(src, candidate.scaleToReferenceQ16);
                    error += std::abs(ref - scaled);
                    ++samples;
                }
            }
            if (samples < 16) continue;
            const int64_t score = error / samples;
            if (score < bestScore) {
                bestScore = score;
                bestDx = dx;
                bestDy = dy;
                bestSamples = samples;
            }
        }
    }

    if (bestSamples == 0 || bestScore == std::numeric_limits<int64_t>::max()) {
        return {0, 0, bestScore, 0.0f};
    }
    const float confidence = std::clamp(1.0f - static_cast<float>(bestScore) / 6500.0f, 0.0f, 1.0f);
    return {bestDx, bestDy, bestScore, confidence};
}

int64_t sharpnessScore(const Frame& frame, int width, int height, int whiteLevel) {
    int64_t sum = 0;
    int samples = 0;
    for (int y = 16; y < height - 16; y += 24) {
        for (int x = 16; x < width - 18; x += 24) {
            const auto* p = frame.mapping.pixels;
            const int a = normalizedQ16(frame, p[y * width + x], x, y, whiteLevel);
            const int b = normalizedQ16(frame, p[y * width + x + 2], x + 2, y, whiteLevel);
            sum += std::abs(a - b);
            ++samples;
        }
    }
    return samples > 0 ? sum / samples : 0;
}

int chooseReference(const std::vector<Frame>& frames, int width, int height, int whiteLevel) {
    if (frames.empty()) return 0;
    long double maxExposure = 0.0L;
    for (const auto& frame : frames) {
        maxExposure = std::max(maxExposure, static_cast<long double>(frame.exposureNs) * std::max(1, frame.iso));
    }
    int best = 0;
    int64_t bestSharpness = -1;
    for (size_t i = 0; i < frames.size(); ++i) {
        const long double exposure = static_cast<long double>(frames[i].exposureNs) * std::max(1, frames[i].iso);
        if (exposure < maxExposure * 0.82L) continue;
        const int64_t score = sharpnessScore(frames[i], width, height, whiteLevel);
        if (score > bestSharpness) {
            bestSharpness = score;
            best = static_cast<int>(i);
        }
    }
    return best;
}

float tonePixel(float x, float hdrStrength, float highlight, float shadow) {
    x = std::clamp(x, 0.0f, 2.0f);

    // Lift useful dark information without flattening the midtones.
    if (shadow > 0.001f) {
        const float lift = std::clamp(shadow, 0.0f, 2.0f) * 0.22f;
        x += lift * (1.0f - std::min(x, 1.0f)) * std::exp(-3.2f * x);
    }

    // Compress recovered HDR headroom into the DNG's numerical range.
    const float shoulder = std::clamp(highlight + hdrStrength * 0.55f, 0.0f, 2.5f);
    if (shoulder > 0.001f) {
        constexpr float knee = 0.68f;
        if (x > knee) {
            const float t = (x - knee) / std::max(0.001f, 1.0f - knee);
            const float k = 1.0f + shoulder * 2.4f;
            const float compressed = (1.0f - std::exp(-k * t)) / (1.0f - std::exp(-k));
            x = knee + (1.0f - knee) * compressed;
        }
    }
    return std::clamp(x, 0.0f, 1.0f);
}

enum class SensorColor : int { R = 0, G = 1, B = 2 };

SensorColor sensorColor(int x, int y, int cfa) {
    const int p = cfaIndex(x, y);
    // Android CameraMetadata SENSOR_INFO_COLOR_FILTER_ARRANGEMENT values:
    // 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. Unknown arrangements fall back to RGGB.
    switch (cfa) {
        case 1: { // GRBG
            static constexpr SensorColor k[4] = {SensorColor::G, SensorColor::R, SensorColor::B, SensorColor::G};
            return k[p];
        }
        case 2: { // GBRG
            static constexpr SensorColor k[4] = {SensorColor::G, SensorColor::B, SensorColor::R, SensorColor::G};
            return k[p];
        }
        case 3: { // BGGR
            static constexpr SensorColor k[4] = {SensorColor::B, SensorColor::G, SensorColor::G, SensorColor::R};
            return k[p];
        }
        case 0:
        default: {
            static constexpr SensorColor k[4] = {SensorColor::R, SensorColor::G, SensorColor::G, SensorColor::B};
            return k[p];
        }
    }
}

void applySensorSaturation(
    std::vector<uint16_t>& raw,
    const Frame& reference,
    int width,
    int height,
    int whiteLevel,
    int cfa,
    float saturation
) {
    const float sat = std::clamp(saturation, 0.0f, 2.5f);
    if (std::abs(sat - 1.0f) < 0.005f) return;

    for (int y = 0; y + 1 < height; y += 2) {
        for (int x = 0; x + 1 < width; x += 2) {
            int rIndex = -1;
            int bIndex = -1;
            int gIndex0 = -1;
            int gIndex1 = -1;
            for (int oy = 0; oy < 2; ++oy) {
                for (int ox = 0; ox < 2; ++ox) {
                    const int index = (y + oy) * width + (x + ox);
                    switch (sensorColor(x + ox, y + oy, cfa)) {
                        case SensorColor::R: rIndex = index; break;
                        case SensorColor::B: bIndex = index; break;
                        case SensorColor::G:
                            if (gIndex0 < 0) gIndex0 = index; else gIndex1 = index;
                            break;
                    }
                }
            }
            if (rIndex < 0 || bIndex < 0 || gIndex0 < 0 || gIndex1 < 0) continue;

            const auto normAt = [&](int index, int px, int py) {
                return normalizedQ16(reference, raw[index], px, py, whiteLevel) / 65535.0f;
            };
            const float g0 = normAt(gIndex0, gIndex0 % width, gIndex0 / width);
            const float g1 = normAt(gIndex1, gIndex1 % width, gIndex1 / width);
            const float g = 0.5f * (g0 + g1);
            const float r = normAt(rIndex, rIndex % width, rIndex / width);
            const float b = normAt(bIndex, bIndex % width, bIndex / width);
            const float rr = std::clamp(g + (r - g) * sat, 0.0f, 1.0f);
            const float bb = std::clamp(g + (b - g) * sat, 0.0f, 1.0f);
            raw[rIndex] = static_cast<uint16_t>(encodedFromQ16(reference, static_cast<int>(rr * 65535.0f + 0.5f), rIndex % width, rIndex / width, whiteLevel));
            raw[bIndex] = static_cast<uint16_t>(encodedFromQ16(reference, static_cast<int>(bb * 65535.0f + 0.5f), bIndex % width, bIndex / width, whiteLevel));
        }
    }
}

void applySameCfaSharpen(
    std::vector<uint16_t>& raw,
    const Frame& reference,
    int width,
    int height,
    int whiteLevel,
    float sharpness
) {
    const float amount = std::clamp(sharpness, 0.0f, 2.0f) * 0.42f;
    if (amount < 0.002f || width < 8 || height < 8) return;
    const std::vector<uint16_t> source = raw;
    for (int y = 2; y < height - 2; ++y) {
        for (int x = 2; x < width - 2; ++x) {
            const int idx = y * width + x;
            const int center = normalizedQ16(reference, source[idx], x, y, whiteLevel);
            const int avg = (
                normalizedQ16(reference, source[idx - 2], x - 2, y, whiteLevel) +
                normalizedQ16(reference, source[idx + 2], x + 2, y, whiteLevel) +
                normalizedQ16(reference, source[idx - 2 * width], x, y - 2, whiteLevel) +
                normalizedQ16(reference, source[idx + 2 * width], x, y + 2, whiteLevel)
            ) >> 2;
            const int sharpened = clampInt(static_cast<int>(center + amount * static_cast<float>(center - avg)), 0, 65535);
            raw[idx] = static_cast<uint16_t>(encodedFromQ16(reference, sharpened, x, y, whiteLevel));
        }
    }
}

int lastCoordinateWithParity(int size, int parity) {
    int v = size - 1;
    if ((v & 1) != parity) --v;
    return std::max(parity, v);
}

uint16_t samplePlane(const std::vector<uint16_t>& src, int width, int height, int planeX, int planeY, int parityX, int parityY) {
    const int maxX = lastCoordinateWithParity(width, parityX);
    const int maxY = lastCoordinateWithParity(height, parityY);
    const int x = clampInt(planeX * 2 + parityX, parityX, maxX);
    const int y = clampInt(planeY * 2 + parityY, parityY, maxY);
    return src[y * width + x];
}

std::vector<uint16_t> upscaleBayer(const std::vector<uint16_t>& src, int width, int height, int factor) {
    if (factor <= 1) return src;
    const int outWidth = width * factor;
    const int outHeight = height * factor;
    std::vector<uint16_t> out(static_cast<size_t>(outWidth) * outHeight);

    for (int y = 0; y < outHeight; ++y) {
        const int parityY = y & 1;
        const float planeY = static_cast<float>((y - parityY) / 2) / factor;
        const int y0 = static_cast<int>(std::floor(planeY));
        const int y1 = y0 + 1;
        const float fy = planeY - y0;
        for (int x = 0; x < outWidth; ++x) {
            const int parityX = x & 1;
            const float planeX = static_cast<float>((x - parityX) / 2) / factor;
            const int x0 = static_cast<int>(std::floor(planeX));
            const int x1 = x0 + 1;
            const float fx = planeX - x0;
            const float a = samplePlane(src, width, height, x0, y0, parityX, parityY);
            const float b = samplePlane(src, width, height, x1, y0, parityX, parityY);
            const float c = samplePlane(src, width, height, x0, y1, parityX, parityY);
            const float d = samplePlane(src, width, height, x1, y1, parityX, parityY);
            const float top = a + (b - a) * fx;
            const float bottom = c + (d - c) * fx;
            out[static_cast<size_t>(y) * outWidth + x] = static_cast<uint16_t>(
                clampInt(static_cast<int>(top + (bottom - top) * fy + 0.5f), 0, 65535)
            );
        }
    }
    return out;
}

bool writeRaw16(const std::string& path, const std::vector<uint16_t>& pixels) {
    const int fd = ::open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const uint8_t* data = reinterpret_cast<const uint8_t*>(pixels.data());
    size_t remaining = pixels.size() * sizeof(uint16_t);
    while (remaining > 0) {
        const ssize_t written = ::write(fd, data, remaining);
        if (written <= 0) {
            ::close(fd);
            return false;
        }
        data += written;
        remaining -= static_cast<size_t>(written);
    }
    fsync(fd);
    ::close(fd);
    return true;
}

std::vector<std::string> stringsFromJava(JNIEnv* env, jobjectArray array) {
    const jsize count = env->GetArrayLength(array);
    std::vector<std::string> out;
    out.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto str = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        const char* chars = env->GetStringUTFChars(str, nullptr);
        out.emplace_back(chars != nullptr ? chars : "");
        if (chars != nullptr) env->ReleaseStringUTFChars(str, chars);
        env->DeleteLocalRef(str);
    }
    return out;
}

std::string stringFromJava(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jlongArray failure(JNIEnv* env, int code) {
    jlong values[5] = {0, 0, 0, 0, code};
    auto result = env->NewLongArray(5);
    env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_camera_processing_raw_NativeComputationalRawEngine_nativeProcess(
    JNIEnv* env,
    jobject,
    jobjectArray inputPaths,
    jlongArray exposureTimesNs,
    jintArray sensitivities,
    jintArray blackLevels,
    jint width,
    jint height,
    jint whiteLevel,
    jint cfaArrangement,
    jboolean hdrEnabled,
    jfloat hdrStrength,
    jfloat highlightProtection,
    jfloat shadowRecovery,
    jfloat denoise,
    jfloat sharpness,
    jfloat saturation,
    jint upscaleFactor,
    jstring outputPath
) {
    if (width <= 0 || height <= 0 || whiteLevel <= 0 || inputPaths == nullptr) {
        return failure(env, -1);
    }

    const std::vector<std::string> paths = stringsFromJava(env, inputPaths);
    const int frameCount = static_cast<int>(paths.size());
    if (frameCount <= 0) return failure(env, -2);
    if (env->GetArrayLength(exposureTimesNs) != frameCount ||
        env->GetArrayLength(sensitivities) != frameCount ||
        env->GetArrayLength(blackLevels) != frameCount * 4) {
        return failure(env, -3);
    }

    std::vector<jlong> exposures(frameCount);
    std::vector<jint> isos(frameCount);
    std::vector<jint> blacks(frameCount * 4);
    env->GetLongArrayRegion(exposureTimesNs, 0, frameCount, exposures.data());
    env->GetIntArrayRegion(sensitivities, 0, frameCount, isos.data());
    env->GetIntArrayRegion(blackLevels, 0, frameCount * 4, blacks.data());

    const size_t expectedBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    std::vector<Frame> frames;
    frames.reserve(frameCount);
    for (int i = 0; i < frameCount; ++i) {
        Frame frame;
        frame.exposureNs = std::max<int64_t>(1, exposures[i]);
        frame.iso = std::max(1, static_cast<int>(isos[i]));
        for (int c = 0; c < 4; ++c) frame.black[c] = std::max(0, static_cast<int>(blacks[i * 4 + c]));
        if (!frame.mapping.openReadOnly(paths[i], expectedBytes)) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "Unable to mmap RAW frame %d", i);
            return failure(env, -10 - i);
        }
        frames.emplace_back(std::move(frame));
    }

    const int referenceIndex = chooseReference(frames, width, height, whiteLevel);
    const long double refProduct = static_cast<long double>(frames[referenceIndex].exposureNs) * frames[referenceIndex].iso;
    for (auto& frame : frames) {
        const long double product = static_cast<long double>(frame.exposureNs) * frame.iso;
        const long double scale = std::clamp(refProduct / std::max<long double>(1.0L, product), 0.0625L, 32.0L);
        frame.scaleToReferenceQ16 = std::max(1, static_cast<int>(scale * kQ16 + 0.5L));
    }

    std::vector<Alignment> alignments(frameCount);
    alignments[referenceIndex] = {0, 0, 0, 1.0f};
    for (int i = 0; i < frameCount; ++i) {
        if (i == referenceIndex) continue;
        alignments[i] = findAlignment(frames[referenceIndex], frames[i], width, height, whiteLevel);
    }

    const Frame& reference = frames[referenceIndex];
    std::vector<uint16_t> fused(static_cast<size_t>(width) * height);
    int64_t accepted = 0;
    int64_t rejected = 0;
    const float denoiseStrength = std::clamp(denoise, 0.0f, 2.0f);
    const bool useHdr = hdrEnabled == JNI_TRUE;
    const float hdr = std::clamp(hdrStrength, 0.0f, 2.0f);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t index = static_cast<size_t>(y) * width + x;
            const int refNorm = normalizedQ16(reference, reference.mapping.pixels[index], x, y, whiteLevel);
            int64_t weighted = static_cast<int64_t>(refNorm) * kReferenceWeight;
            int weightSum = kReferenceWeight;

            for (int i = 0; i < frameCount; ++i) {
                if (i == referenceIndex) continue;
                const Alignment& alignment = alignments[i];
                if (alignment.confidence < 0.12f) {
                    ++rejected;
                    continue;
                }
                const int sx = x + alignment.dx;
                const int sy = y + alignment.dy;
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                    ++rejected;
                    continue;
                }
                const Frame& candidate = frames[i];
                const int srcNorm = normalizedQ16(candidate, candidate.mapping.pixels[static_cast<size_t>(sy) * width + sx], sx, sy, whiteLevel);
                if (srcNorm >= 65400) {
                    ++rejected;
                    continue;
                }
                const int normalized = scaledToReference(srcNorm, candidate.scaleToReferenceQ16);
                const int delta = std::abs(normalized - refNorm);
                const bool bracket = std::abs(candidate.scaleToReferenceQ16 - kQ16) > 8192;
                const bool highlightRecovery = useHdr && bracket && refNorm > 56000 && srcNorm < 61000;
                const int threshold = static_cast<int>(1400.0f + denoiseStrength * 3600.0f + refNorm * 0.025f + (highlightRecovery ? 7000.0f * hdr : 0.0f));
                if (!highlightRecovery && delta > threshold) {
                    ++rejected;
                    continue;
                }
                int weight = bracket ? (useHdr ? 2 : 1) : 3;
                if (alignment.confidence < 0.4f) weight = std::max(1, weight / 2);
                if (delta > threshold / 2) weight = std::max(1, weight / 2);
                weighted += static_cast<int64_t>(normalized) * weight;
                weightSum += weight;
                ++accepted;
            }

            const int merged = static_cast<int>(weighted / std::max(1, weightSum));
            const float tone = tonePixel(
                static_cast<float>(merged) / 65535.0f,
                useHdr ? hdr : 0.0f,
                std::clamp(highlightProtection, 0.0f, 2.0f),
                std::clamp(shadowRecovery, 0.0f, 2.0f)
            );
            fused[index] = static_cast<uint16_t>(encodedFromQ16(reference, static_cast<int>(tone * 65535.0f + 0.5f), x, y, whiteLevel));
        }
    }

    applySensorSaturation(fused, reference, width, height, whiteLevel, cfaArrangement, saturation);
    applySameCfaSharpen(fused, reference, width, height, whiteLevel, sharpness);

    const int factor = clampInt(upscaleFactor, 1, 4);
    std::vector<uint16_t> output = upscaleBayer(fused, width, height, factor);
    const int outputWidth = width * factor;
    const int outputHeight = height * factor;
    const std::string outPath = stringFromJava(env, outputPath);
    if (!writeRaw16(outPath, output)) return failure(env, -30);

    // Header: width, height, accepted, rejected, referenceIndex; then dx/dy/confidence(0..10000).
    std::vector<jlong> metrics;
    metrics.reserve(5 + frameCount * 3);
    metrics.push_back(outputWidth);
    metrics.push_back(outputHeight);
    metrics.push_back(accepted);
    metrics.push_back(rejected);
    metrics.push_back(referenceIndex);
    for (const auto& alignment : alignments) {
        metrics.push_back(alignment.dx);
        metrics.push_back(alignment.dy);
        metrics.push_back(static_cast<jlong>(std::clamp(alignment.confidence, 0.0f, 1.0f) * 10000.0f + 0.5f));
    }

    auto result = env->NewLongArray(static_cast<jsize>(metrics.size()));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(metrics.size()), metrics.data());
    return result;
}
