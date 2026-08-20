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
constexpr const char* TAG = "CameraRawNative";
constexpr int Q16 = 65536;
constexpr int ALIGN_RADIUS = 8;
constexpr int ALIGN_STEP = 28;
constexpr int ALIGN_BORDER = 20;

struct MappedRaw {
    int fd = -1;
    size_t bytes = 0;
    const uint16_t* data = nullptr;

    MappedRaw() = default;
    MappedRaw(const MappedRaw&) = delete;
    MappedRaw& operator=(const MappedRaw&) = delete;
    MappedRaw(MappedRaw&& other) noexcept {
        fd = other.fd;
        bytes = other.bytes;
        data = other.data;
        other.fd = -1;
        other.bytes = 0;
        other.data = nullptr;
    }
    MappedRaw& operator=(MappedRaw&& other) noexcept {
        if (this != &other) {
            closeNow();
            fd = other.fd;
            bytes = other.bytes;
            data = other.data;
            other.fd = -1;
            other.bytes = 0;
            other.data = nullptr;
        }
        return *this;
    }
    ~MappedRaw() { closeNow(); }

    bool openFile(const std::string& path, size_t expectedBytes) {
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
            closeNow();
            return false;
        }
        data = static_cast<const uint16_t*>(ptr);
        return true;
    }

    void closeNow() {
        if (data != nullptr) {
            munmap(const_cast<uint16_t*>(data), bytes);
            data = nullptr;
        }
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
        bytes = 0;
    }
};

struct Frame {
    MappedRaw raw;
    int64_t exposureNs = 1;
    int iso = 100;
    std::array<int, 4> black{0, 0, 0, 0};
    int exposureScaleQ16 = Q16;
};

struct Align {
    int dx = 0;
    int dy = 0;
    float confidence = 1.0f;
};

inline int clampi(int v, int lo, int hi) { return std::min(hi, std::max(lo, v)); }
inline int cfaIndex(int x, int y) { return ((y & 1) << 1) | (x & 1); }

int normalize16(const Frame& f, int raw, int x, int y, int white) {
    const int black = f.black[cfaIndex(x, y)];
    const int range = std::max(1, white - black);
    const int signal = std::max(0, raw - black);
    return clampi(static_cast<int>((static_cast<int64_t>(signal) * 65535 + range / 2) / range), 0, 65535);
}

int encode16(const Frame& f, int norm, int x, int y, int white) {
    const int black = f.black[cfaIndex(x, y)];
    const int range = std::max(1, white - black);
    const int signal = static_cast<int>((static_cast<int64_t>(clampi(norm, 0, 65535)) * range + 32767) / 65535);
    return clampi(black + signal, 0, std::min(65535, white));
}

int scaleExposure(int v, int scaleQ16) {
    return clampi(static_cast<int>((static_cast<int64_t>(v) * scaleQ16 + 32768) >> 16), 0, 131070);
}

int blockLuma(const Frame& f, int x, int y, int w, int h, int white) {
    const int x1 = std::min(w - 1, x + 1);
    const int y1 = std::min(h - 1, y + 1);
    const auto* p = f.raw.data;
    return (
        normalize16(f, p[y * w + x], x, y, white) +
        normalize16(f, p[y * w + x1], x1, y, white) +
        normalize16(f, p[y1 * w + x], x, y1, white) +
        normalize16(f, p[y1 * w + x1], x1, y1, white)
    ) >> 2;
}

Align estimateAlignment(const Frame& ref, const Frame& src, int w, int h, int white) {
    int bestDx = 0;
    int bestDy = 0;
    int64_t best = std::numeric_limits<int64_t>::max();
    int bestSamples = 0;

    for (int dy = -ALIGN_RADIUS; dy <= ALIGN_RADIUS; dy += 2) {
        for (int dx = -ALIGN_RADIUS; dx <= ALIGN_RADIUS; dx += 2) {
            int64_t error = 0;
            int samples = 0;
            for (int y = ALIGN_BORDER & ~1; y < h - ALIGN_BORDER; y += ALIGN_STEP) {
                for (int x = ALIGN_BORDER & ~1; x < w - ALIGN_BORDER; x += ALIGN_STEP) {
                    const int sx = x + dx;
                    const int sy = y + dy;
                    if (sx < 0 || sy < 0 || sx + 1 >= w || sy + 1 >= h) continue;
                    const int a = blockLuma(ref, x, y, w, h, white);
                    if (a < 900 || a > 64500) continue;
                    const int b = scaleExposure(blockLuma(src, sx, sy, w, h, white), src.exposureScaleQ16);
                    error += std::abs(a - b);
                    ++samples;
                }
            }
            if (samples < 16) continue;
            const int64_t score = error / samples;
            if (score < best) {
                best = score;
                bestDx = dx;
                bestDy = dy;
                bestSamples = samples;
            }
        }
    }

    if (bestSamples == 0) return {0, 0, 0.0f};
    const float confidence = std::clamp(1.0f - static_cast<float>(best) / 6500.0f, 0.0f, 1.0f);
    return {bestDx, bestDy, confidence};
}

int64_t sharpnessScore(const Frame& f, int w, int h, int white) {
    int64_t score = 0;
    int samples = 0;
    for (int y = 18; y < h - 18; y += 24) {
        for (int x = 18; x < w - 20; x += 24) {
            const int a = normalize16(f, f.raw.data[y * w + x], x, y, white);
            const int b = normalize16(f, f.raw.data[y * w + x + 2], x + 2, y, white);
            score += std::abs(a - b);
            ++samples;
        }
    }
    return samples > 0 ? score / samples : 0;
}

int chooseReference(const std::vector<Frame>& frames, int w, int h, int white) {
    long double maxExposure = 0.0L;
    for (const auto& f : frames) {
        maxExposure = std::max(maxExposure, static_cast<long double>(f.exposureNs) * std::max(1, f.iso));
    }
    int best = 0;
    int64_t bestSharpness = -1;
    for (size_t i = 0; i < frames.size(); ++i) {
        const long double e = static_cast<long double>(frames[i].exposureNs) * std::max(1, frames[i].iso);
        if (e < maxExposure * 0.82L) continue;
        const int64_t sharp = sharpnessScore(frames[i], w, h, white);
        if (sharp > bestSharpness) {
            bestSharpness = sharp;
            best = static_cast<int>(i);
        }
    }
    return best;
}

float tone(float x, float hdr, float highlights, float shadows) {
    x = std::clamp(x, 0.0f, 2.0f);
    if (shadows > 0.001f) {
        x += std::clamp(shadows, 0.0f, 2.0f) * 0.22f *
            (1.0f - std::min(x, 1.0f)) * std::exp(-3.2f * x);
    }
    const float shoulder = std::clamp(highlights + hdr * 0.55f, 0.0f, 2.5f);
    if (shoulder > 0.001f && x > 0.68f) {
        constexpr float knee = 0.68f;
        const float t = (x - knee) / (1.0f - knee);
        const float k = 1.0f + shoulder * 2.4f;
        const float c = (1.0f - std::exp(-k * t)) / (1.0f - std::exp(-k));
        x = knee + (1.0f - knee) * c;
    }
    return std::clamp(x, 0.0f, 1.0f);
}

enum class Color { R, G, B };
Color colorAt(int x, int y, int cfa) {
    const int p = cfaIndex(x, y);
    static constexpr Color rggb[4] = {Color::R, Color::G, Color::G, Color::B};
    static constexpr Color grbg[4] = {Color::G, Color::R, Color::B, Color::G};
    static constexpr Color gbrg[4] = {Color::G, Color::B, Color::R, Color::G};
    static constexpr Color bggr[4] = {Color::B, Color::G, Color::G, Color::R};
    switch (cfa) {
        case 1: return grbg[p];
        case 2: return gbrg[p];
        case 3: return bggr[p];
        default: return rggb[p];
    }
}

void applySaturation(std::vector<uint16_t>& raw, const Frame& ref, int w, int h, int white, int cfa, float saturation) {
    const float sat = std::clamp(saturation, 0.0f, 2.5f);
    if (std::abs(sat - 1.0f) < 0.005f) return;
    for (int y = 0; y + 1 < h; y += 2) {
        for (int x = 0; x + 1 < w; x += 2) {
            int ri = -1, bi = -1, g0 = -1, g1 = -1;
            for (int oy = 0; oy < 2; ++oy) {
                for (int ox = 0; ox < 2; ++ox) {
                    const int idx = (y + oy) * w + x + ox;
                    switch (colorAt(x + ox, y + oy, cfa)) {
                        case Color::R: ri = idx; break;
                        case Color::B: bi = idx; break;
                        case Color::G: if (g0 < 0) g0 = idx; else g1 = idx; break;
                    }
                }
            }
            if (ri < 0 || bi < 0 || g0 < 0 || g1 < 0) continue;
            auto norm = [&](int idx) {
                return normalize16(ref, raw[idx], idx % w, idx / w, white) / 65535.0f;
            };
            const float g = (norm(g0) + norm(g1)) * 0.5f;
            const float r = std::clamp(g + (norm(ri) - g) * sat, 0.0f, 1.0f);
            const float b = std::clamp(g + (norm(bi) - g) * sat, 0.0f, 1.0f);
            raw[ri] = static_cast<uint16_t>(encode16(ref, static_cast<int>(r * 65535.0f + 0.5f), ri % w, ri / w, white));
            raw[bi] = static_cast<uint16_t>(encode16(ref, static_cast<int>(b * 65535.0f + 0.5f), bi % w, bi / w, white));
        }
    }
}

void applySharpness(std::vector<uint16_t>& raw, const Frame& ref, int w, int h, int white, float sharpness) {
    const float amount = std::clamp(sharpness, 0.0f, 2.0f) * 0.42f;
    if (amount < 0.002f || w < 8 || h < 8) return;
    const auto src = raw;
    for (int y = 2; y < h - 2; ++y) {
        for (int x = 2; x < w - 2; ++x) {
            const int i = y * w + x;
            const int center = normalize16(ref, src[i], x, y, white);
            const int avg = (
                normalize16(ref, src[i - 2], x - 2, y, white) +
                normalize16(ref, src[i + 2], x + 2, y, white) +
                normalize16(ref, src[i - 2 * w], x, y - 2, white) +
                normalize16(ref, src[i + 2 * w], x, y + 2, white)
            ) >> 2;
            raw[i] = static_cast<uint16_t>(encode16(ref, clampi(static_cast<int>(center + amount * (center - avg)), 0, 65535), x, y, white));
        }
    }
}

int parityLast(int size, int parity) {
    int v = size - 1;
    if ((v & 1) != parity) --v;
    return std::max(parity, v);
}

uint16_t samplePlane(const std::vector<uint16_t>& src, int w, int h, int px, int py, int parityX, int parityY) {
    const int x = clampi(px * 2 + parityX, parityX, parityLast(w, parityX));
    const int y = clampi(py * 2 + parityY, parityY, parityLast(h, parityY));
    return src[y * w + x];
}

std::vector<uint16_t> upscale(const std::vector<uint16_t>& src, int w, int h, int factor) {
    if (factor <= 1) return src;
    const int ow = w * factor;
    const int oh = h * factor;
    std::vector<uint16_t> out(static_cast<size_t>(ow) * oh);
    for (int y = 0; y < oh; ++y) {
        const int py = y & 1;
        const float sy = static_cast<float>((y - py) / 2) / factor;
        const int y0 = static_cast<int>(std::floor(sy));
        const float fy = sy - y0;
        for (int x = 0; x < ow; ++x) {
            const int px = x & 1;
            const float sx = static_cast<float>((x - px) / 2) / factor;
            const int x0 = static_cast<int>(std::floor(sx));
            const float fx = sx - x0;
            const float a = samplePlane(src, w, h, x0, y0, px, py);
            const float b = samplePlane(src, w, h, x0 + 1, y0, px, py);
            const float c = samplePlane(src, w, h, x0, y0 + 1, px, py);
            const float d = samplePlane(src, w, h, x0 + 1, y0 + 1, px, py);
            const float top = a + (b - a) * fx;
            const float bottom = c + (d - c) * fx;
            out[static_cast<size_t>(y) * ow + x] = static_cast<uint16_t>(clampi(static_cast<int>(top + (bottom - top) * fy + 0.5f), 0, 65535));
        }
    }
    return out;
}

struct Cropped {
    std::vector<uint16_t> pixels;
    int width = 0;
    int height = 0;
};

Cropped cropAspect(std::vector<uint16_t>&& src, int w, int h, float targetAspect) {
    float a = std::isfinite(targetAspect) && targetAspect > 0.0f ? targetAspect : 4.0f / 3.0f;
    if (a < 1.0f) a = 1.0f / a;
    const float source = static_cast<float>(std::max(w, h)) / std::max(1, std::min(w, h));
    if (std::abs(source - a) < 0.01f) return {std::move(src), w, h};

    int nw = w;
    int nh = h;
    if (w >= h) {
        if (source > a) nw = static_cast<int>(h * a);
        else nh = static_cast<int>(w / a);
    } else {
        if (source > a) nh = static_cast<int>(w * a);
        else nw = static_cast<int>(h / a);
    }
    nw = std::max(2, nw & ~1);
    nh = std::max(2, nh & ~1);
    const int left = ((w - nw) / 2) & ~1;
    const int top = ((h - nh) / 2) & ~1;
    std::vector<uint16_t> out(static_cast<size_t>(nw) * nh);
    for (int y = 0; y < nh; ++y) {
        std::copy_n(src.begin() + static_cast<size_t>(top + y) * w + left, nw, out.begin() + static_cast<size_t>(y) * nw);
    }
    return {std::move(out), nw, nh};
}

bool writeRaw(const std::string& path, const std::vector<uint16_t>& pixels) {
    const int fd = ::open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const uint8_t* p = reinterpret_cast<const uint8_t*>(pixels.data());
    size_t remaining = pixels.size() * sizeof(uint16_t);
    while (remaining > 0) {
        const ssize_t n = ::write(fd, p, remaining);
        if (n <= 0) { ::close(fd); return false; }
        p += n;
        remaining -= static_cast<size_t>(n);
    }
    fsync(fd);
    ::close(fd);
    return true;
}

std::vector<std::string> javaStrings(JNIEnv* env, jobjectArray array) {
    const jsize n = env->GetArrayLength(array);
    std::vector<std::string> out;
    out.reserve(n);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        const char* chars = env->GetStringUTFChars(s, nullptr);
        out.emplace_back(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(s, chars);
        env->DeleteLocalRef(s);
    }
    return out;
}

std::string javaString(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jlongArray fail(JNIEnv* env, int code) {
    jlong values[5] = {0, 0, 0, 0, code};
    auto out = env->NewLongArray(5);
    env->SetLongArrayRegion(out, 0, 5, values);
    return out;
}
}  // namespace

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
    jfloat targetAspect,
    jstring outputPath
) {
    if (!inputPaths || width <= 0 || height <= 0 || whiteLevel <= 0) return fail(env, -1);
    const auto paths = javaStrings(env, inputPaths);
    const int count = static_cast<int>(paths.size());
    if (count <= 0) return fail(env, -2);
    if (env->GetArrayLength(exposureTimesNs) != count || env->GetArrayLength(sensitivities) != count || env->GetArrayLength(blackLevels) != count * 4) return fail(env, -3);

    std::vector<jlong> exposures(count);
    std::vector<jint> isos(count);
    std::vector<jint> blacks(count * 4);
    env->GetLongArrayRegion(exposureTimesNs, 0, count, exposures.data());
    env->GetIntArrayRegion(sensitivities, 0, count, isos.data());
    env->GetIntArrayRegion(blackLevels, 0, count * 4, blacks.data());

    const size_t expectedBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    std::vector<Frame> frames;
    frames.reserve(count);
    for (int i = 0; i < count; ++i) {
        Frame f;
        f.exposureNs = std::max<int64_t>(1, exposures[i]);
        f.iso = std::max(1, static_cast<int>(isos[i]));
        for (int c = 0; c < 4; ++c) f.black[c] = std::max(0, static_cast<int>(blacks[i * 4 + c]));
        if (!f.raw.openFile(paths[i], expectedBytes)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "mmap failed for RAW frame %d", i);
            return fail(env, -10 - i);
        }
        frames.emplace_back(std::move(f));
    }

    const int refIndex = chooseReference(frames, width, height, whiteLevel);
    const long double refProduct = static_cast<long double>(frames[refIndex].exposureNs) * frames[refIndex].iso;
    for (auto& f : frames) {
        const long double product = std::max<long double>(1.0L, static_cast<long double>(f.exposureNs) * f.iso);
        const long double scale = std::clamp(refProduct / product, 0.0625L, 32.0L);
        f.exposureScaleQ16 = std::max(1, static_cast<int>(scale * Q16 + 0.5L));
    }

    std::vector<Align> alignments(count);
    alignments[refIndex] = {0, 0, 1.0f};
    for (int i = 0; i < count; ++i) if (i != refIndex) alignments[i] = estimateAlignment(frames[refIndex], frames[i], width, height, whiteLevel);

    const Frame& ref = frames[refIndex];
    std::vector<uint16_t> fused(static_cast<size_t>(width) * height);
    int64_t accepted = 0;
    int64_t rejected = 0;
    const float dn = std::clamp(denoise, 0.0f, 2.0f);
    const bool hdr = hdrEnabled == JNI_TRUE;
    const float hdrAmount = hdr ? std::clamp(hdrStrength, 0.0f, 2.0f) : 0.0f;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t index = static_cast<size_t>(y) * width + x;
            const int r = normalize16(ref, ref.raw.data[index], x, y, whiteLevel);
            int64_t sum = static_cast<int64_t>(r) * 4;
            int weights = 4;
            for (int i = 0; i < count; ++i) {
                if (i == refIndex) continue;
                const auto& a = alignments[i];
                if (a.confidence < 0.12f) { ++rejected; continue; }
                const int sx = x + a.dx;
                const int sy = y + a.dy;
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) { ++rejected; continue; }
                const Frame& src = frames[i];
                const int source = normalize16(src, src.raw.data[static_cast<size_t>(sy) * width + sx], sx, sy, whiteLevel);
                if (source >= 65400) { ++rejected; continue; }
                const int n = scaleExposure(source, src.exposureScaleQ16);
                const int delta = std::abs(n - r);
                const bool bracket = std::abs(src.exposureScaleQ16 - Q16) > 8192;
                const bool recoverHighlight = hdr && bracket && r > 56000 && source < 61000;
                const int threshold = static_cast<int>(1400.0f + dn * 3600.0f + r * 0.025f + (recoverHighlight ? 7000.0f * hdrAmount : 0.0f));
                if (!recoverHighlight && delta > threshold) { ++rejected; continue; }
                int weight = bracket ? (hdr ? 2 : 1) : 3;
                if (a.confidence < 0.4f) weight = std::max(1, weight / 2);
                if (delta > threshold / 2) weight = std::max(1, weight / 2);
                sum += static_cast<int64_t>(n) * weight;
                weights += weight;
                ++accepted;
            }
            const int merged = static_cast<int>(sum / std::max(1, weights));
            const float t = tone(static_cast<float>(merged) / 65535.0f, hdrAmount, highlightProtection, shadowRecovery);
            fused[index] = static_cast<uint16_t>(encode16(ref, static_cast<int>(t * 65535.0f + 0.5f), x, y, whiteLevel));
        }
    }

    applySaturation(fused, ref, width, height, whiteLevel, cfaArrangement, saturation);
    applySharpness(fused, ref, width, height, whiteLevel, sharpness);

    const int factor = clampi(upscaleFactor, 1, 4);
    auto scaled = upscale(fused, width, height, factor);
    auto finalImage = cropAspect(std::move(scaled), width * factor, height * factor, targetAspect);
    if (!writeRaw(javaString(env, outputPath), finalImage.pixels)) return fail(env, -30);

    std::vector<jlong> metrics;
    metrics.reserve(5 + count * 3);
    metrics.push_back(finalImage.width);
    metrics.push_back(finalImage.height);
    metrics.push_back(accepted);
    metrics.push_back(rejected);
    metrics.push_back(refIndex);
    for (const auto& a : alignments) {
        metrics.push_back(a.dx);
        metrics.push_back(a.dy);
        metrics.push_back(static_cast<jlong>(std::clamp(a.confidence, 0.0f, 1.0f) * 10000.0f + 0.5f));
    }
    auto result = env->NewLongArray(static_cast<jsize>(metrics.size()));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(metrics.size()), metrics.data());
    return result;
}
