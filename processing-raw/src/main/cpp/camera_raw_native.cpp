#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {
constexpr const char* TAG = "CameraRawNative";
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
    MappedRaw(MappedRaw&& other) noexcept { *this = std::move(other); }
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
    int white = 65535;
    std::array<float, 4> gains{1.f, 1.f, 1.f, 1.f};
    std::array<float, 9> transform{1.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 1.f};
    float exposureScale = 1.f;
};

struct Align {
    int dx = 0;
    int dy = 0;
    float confidence = 1.f;
};

struct RgbImage {
    int width = 0;
    int height = 0;
    std::vector<float> pixels; // interleaved linear RGB
};

inline int clampi(int value, int lo, int hi) { return std::min(hi, std::max(lo, value)); }
inline float clampf(float value, float lo, float hi) { return std::min(hi, std::max(lo, value)); }
inline int cfaIndex(int x, int y) { return ((y & 1) << 1) | (x & 1); }

float normalize(const Frame& frame, uint16_t raw, int x, int y) {
    const int black = frame.black[cfaIndex(x, y)];
    const int range = std::max(1, frame.white - black);
    return clampf(static_cast<float>(std::max(0, static_cast<int>(raw) - black)) / range, 0.f, 1.f);
}

float blockLuma(const Frame& frame, int x, int y, int width, int height) {
    const int x1 = std::min(width - 1, x + 1);
    const int y1 = std::min(height - 1, y + 1);
    const auto* p = frame.raw.data;
    return 0.25f * (
        normalize(frame, p[y * width + x], x, y) +
        normalize(frame, p[y * width + x1], x1, y) +
        normalize(frame, p[y1 * width + x], x, y1) +
        normalize(frame, p[y1 * width + x1], x1, y1)
    );
}

Align estimateAlignment(const Frame& ref, const Frame& src, int width, int height) {
    int bestDx = 0;
    int bestDy = 0;
    double best = std::numeric_limits<double>::max();
    int bestSamples = 0;

    for (int dy = -ALIGN_RADIUS; dy <= ALIGN_RADIUS; dy += 2) {
        for (int dx = -ALIGN_RADIUS; dx <= ALIGN_RADIUS; dx += 2) {
            double error = 0.0;
            int samples = 0;
            for (int y = ALIGN_BORDER & ~1; y < height - ALIGN_BORDER; y += ALIGN_STEP) {
                for (int x = ALIGN_BORDER & ~1; x < width - ALIGN_BORDER; x += ALIGN_STEP) {
                    const int sx = x + dx;
                    const int sy = y + dy;
                    if (sx < 0 || sy < 0 || sx + 1 >= width || sy + 1 >= height) continue;
                    const float a = blockLuma(ref, x, y, width, height);
                    if (a < 0.015f || a > 0.985f) continue;
                    const float b = blockLuma(src, sx, sy, width, height) * src.exposureScale;
                    error += std::abs(a - b);
                    ++samples;
                }
            }
            if (samples < 16) continue;
            const double score = error / samples;
            if (score < best) {
                best = score;
                bestDx = dx;
                bestDy = dy;
                bestSamples = samples;
            }
        }
    }
    if (bestSamples == 0) return {0, 0, 0.f};
    const float confidence = clampf(1.f - static_cast<float>(best) / 0.10f, 0.f, 1.f);
    return {bestDx, bestDy, confidence};
}

int64_t sharpnessScore(const Frame& frame, int width, int height) {
    double score = 0.0;
    int samples = 0;
    for (int y = 18; y < height - 18; y += 24) {
        for (int x = 18; x < width - 20; x += 24) {
            const float a = normalize(frame, frame.raw.data[y * width + x], x, y);
            const float b = normalize(frame, frame.raw.data[y * width + x + 2], x + 2, y);
            score += std::abs(a - b);
            ++samples;
        }
    }
    return samples > 0 ? static_cast<int64_t>((score / samples) * 1000000.0) : 0;
}

int chooseReference(const std::vector<Frame>& frames, int width, int height) {
    long double maxExposure = 0.0L;
    for (const auto& frame : frames) {
        maxExposure = std::max(maxExposure, static_cast<long double>(frame.exposureNs) * std::max(1, frame.iso));
    }
    int best = 0;
    int64_t bestSharpness = -1;
    for (size_t i = 0; i < frames.size(); ++i) {
        const long double e = static_cast<long double>(frames[i].exposureNs) * std::max(1, frames[i].iso);
        if (e < maxExposure * 0.82L) continue;
        const int64_t sharpness = sharpnessScore(frames[i], width, height);
        if (sharpness > bestSharpness) {
            bestSharpness = sharpness;
            best = static_cast<int>(i);
        }
    }
    return best;
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

float averageValid(const std::vector<float>& src, int width, int height, const std::vector<std::pair<int,int>>& points) {
    float sum = 0.f;
    int count = 0;
    for (const auto& point : points) {
        if (point.first < 0 || point.second < 0 || point.first >= width || point.second >= height) continue;
        sum += src[static_cast<size_t>(point.second) * width + point.first];
        ++count;
    }
    return count > 0 ? sum / count : 0.f;
}

RgbImage demosaic(
    const std::vector<float>& sensor,
    int width,
    int height,
    int cfa,
    const Frame& reference
) {
    RgbImage out;
    out.width = width;
    out.height = height;
    out.pixels.resize(static_cast<size_t>(width) * height * 3);
    const float gGain = 0.5f * (reference.gains[1] + reference.gains[2]);

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const float center = sensor[static_cast<size_t>(y) * width + x];
            float r = 0.f, g = 0.f, b = 0.f;
            const Color here = colorAt(x, y, cfa);
            if (here == Color::R) {
                r = center;
                g = averageValid(sensor, width, height, {{x-1,y},{x+1,y},{x,y-1},{x,y+1}});
                b = averageValid(sensor, width, height, {{x-1,y-1},{x+1,y-1},{x-1,y+1},{x+1,y+1}});
            } else if (here == Color::B) {
                b = center;
                g = averageValid(sensor, width, height, {{x-1,y},{x+1,y},{x,y-1},{x,y+1}});
                r = averageValid(sensor, width, height, {{x-1,y-1},{x+1,y-1},{x-1,y+1},{x+1,y+1}});
            } else {
                g = center;
                const bool redHorizontal =
                    (x > 0 && colorAt(x - 1, y, cfa) == Color::R) ||
                    (x + 1 < width && colorAt(x + 1, y, cfa) == Color::R);
                if (redHorizontal) {
                    r = averageValid(sensor, width, height, {{x-1,y},{x+1,y}});
                    b = averageValid(sensor, width, height, {{x,y-1},{x,y+1}});
                } else {
                    r = averageValid(sensor, width, height, {{x,y-1},{x,y+1}});
                    b = averageValid(sensor, width, height, {{x-1,y},{x+1,y}});
                }
            }

            r *= reference.gains[0];
            g *= gGain;
            b *= reference.gains[3];

            const auto& m = reference.transform;
            float lr = m[0] * r + m[1] * g + m[2] * b;
            float lg = m[3] * r + m[4] * g + m[5] * b;
            float lb = m[6] * r + m[7] * g + m[8] * b;
            const size_t i = (static_cast<size_t>(y) * width + x) * 3;
            out.pixels[i] = std::max(0.f, lr);
            out.pixels[i + 1] = std::max(0.f, lg);
            out.pixels[i + 2] = std::max(0.f, lb);
        }
    }
    return out;
}

float toneLuma(float x, float hdr, float highlights, float shadows) {
    x = std::max(0.f, x);
    if (shadows > 0.001f) {
        x += clampf(shadows, 0.f, 2.f) * 0.20f * (1.f - std::min(x, 1.f)) * std::exp(-3.0f * x);
    }
    const float shoulder = clampf(highlights + hdr * 0.55f, 0.f, 2.5f);
    if (shoulder > 0.001f && x > 0.68f) {
        constexpr float knee = 0.68f;
        const float t = std::max(0.f, (x - knee) / (1.f - knee));
        const float k = 1.f + shoulder * 2.4f;
        const float c = (1.f - std::exp(-k * t)) / (1.f - std::exp(-k));
        x = knee + (1.f - knee) * c;
    }
    return clampf(x, 0.f, 1.f);
}

void applyColorControls(RgbImage& image, float hdr, float highlights, float shadows, float saturation) {
    const float sat = clampf(saturation, 0.f, 2.5f);
    const size_t pixels = static_cast<size_t>(image.width) * image.height;
    for (size_t p = 0; p < pixels; ++p) {
        float& r = image.pixels[p * 3];
        float& g = image.pixels[p * 3 + 1];
        float& b = image.pixels[p * 3 + 2];
        const float y = std::max(0.f, 0.2126f * r + 0.7152f * g + 0.0722f * b);
        const float target = toneLuma(y, hdr, highlights, shadows);
        const float scale = y > 1e-6f ? target / y : 1.f;
        r *= scale; g *= scale; b *= scale;
        const float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = clampf(l + (r - l) * sat, 0.f, 1.f);
        g = clampf(l + (g - l) * sat, 0.f, 1.f);
        b = clampf(l + (b - l) * sat, 0.f, 1.f);
    }
}

void applySharpness(RgbImage& image, float sharpness) {
    const float amount = clampf(sharpness, 0.f, 2.f) * 0.45f;
    if (amount < 0.002f || image.width < 3 || image.height < 3) return;
    const auto src = image.pixels;
    const int width = image.width;
    const int height = image.height;
    auto lumaAt = [&](int x, int y) {
        const size_t i = (static_cast<size_t>(y) * width + x) * 3;
        return 0.2126f * src[i] + 0.7152f * src[i + 1] + 0.0722f * src[i + 2];
    };
    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            const size_t i = (static_cast<size_t>(y) * width + x) * 3;
            const float center = lumaAt(x, y);
            const float avg = 0.25f * (lumaAt(x-1,y) + lumaAt(x+1,y) + lumaAt(x,y-1) + lumaAt(x,y+1));
            const float target = clampf(center + amount * (center - avg), 0.f, 1.f);
            const float scale = center > 1e-6f ? target / center : 1.f;
            image.pixels[i] = clampf(src[i] * scale, 0.f, 1.f);
            image.pixels[i + 1] = clampf(src[i + 1] * scale, 0.f, 1.f);
            image.pixels[i + 2] = clampf(src[i + 2] * scale, 0.f, 1.f);
        }
    }
}

RgbImage upscaleRgb(const RgbImage& src, int factor) {
    if (factor <= 1) return src;
    RgbImage out;
    out.width = src.width * factor;
    out.height = src.height * factor;
    out.pixels.resize(static_cast<size_t>(out.width) * out.height * 3);
    for (int y = 0; y < out.height; ++y) {
        const float sy = static_cast<float>(y) / factor;
        const int y0 = clampi(static_cast<int>(std::floor(sy)), 0, src.height - 1);
        const int y1 = std::min(src.height - 1, y0 + 1);
        const float fy = sy - y0;
        for (int x = 0; x < out.width; ++x) {
            const float sx = static_cast<float>(x) / factor;
            const int x0 = clampi(static_cast<int>(std::floor(sx)), 0, src.width - 1);
            const int x1 = std::min(src.width - 1, x0 + 1);
            const float fx = sx - x0;
            for (int c = 0; c < 3; ++c) {
                const float a = src.pixels[(static_cast<size_t>(y0) * src.width + x0) * 3 + c];
                const float b = src.pixels[(static_cast<size_t>(y0) * src.width + x1) * 3 + c];
                const float d = src.pixels[(static_cast<size_t>(y1) * src.width + x0) * 3 + c];
                const float e = src.pixels[(static_cast<size_t>(y1) * src.width + x1) * 3 + c];
                const float top = a + (b - a) * fx;
                const float bottom = d + (e - d) * fx;
                out.pixels[(static_cast<size_t>(y) * out.width + x) * 3 + c] = top + (bottom - top) * fy;
            }
        }
    }
    return out;
}

RgbImage cropAspect(RgbImage&& src, float targetAspect) {
    float aspect = std::isfinite(targetAspect) && targetAspect > 0.f ? targetAspect : 4.f / 3.f;
    if (aspect < 1.f) aspect = 1.f / aspect;
    const float source = static_cast<float>(std::max(src.width, src.height)) / std::max(1, std::min(src.width, src.height));
    if (std::abs(source - aspect) < 0.005f) return std::move(src);

    int newWidth = src.width;
    int newHeight = src.height;
    if (src.width >= src.height) {
        if (source > aspect) newWidth = std::max(1, static_cast<int>(src.height * aspect));
        else newHeight = std::max(1, static_cast<int>(src.width / aspect));
    } else {
        if (source > aspect) newHeight = std::max(1, static_cast<int>(src.width * aspect));
        else newWidth = std::max(1, static_cast<int>(src.height / aspect));
    }
    const int left = (src.width - newWidth) / 2;
    const int top = (src.height - newHeight) / 2;
    RgbImage out;
    out.width = newWidth;
    out.height = newHeight;
    out.pixels.resize(static_cast<size_t>(newWidth) * newHeight * 3);
    for (int y = 0; y < newHeight; ++y) {
        const size_t sourceOffset = (static_cast<size_t>(top + y) * src.width + left) * 3;
        const size_t targetOffset = static_cast<size_t>(y) * newWidth * 3;
        std::copy_n(src.pixels.begin() + sourceOffset, static_cast<size_t>(newWidth) * 3, out.pixels.begin() + targetOffset);
    }
    return out;
}

void appendU16(std::vector<uint8_t>& out, uint16_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xff));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
}
void appendU32(std::vector<uint8_t>& out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xff));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xff));
    out.push_back(static_cast<uint8_t>((value >> 16) & 0xff));
    out.push_back(static_cast<uint8_t>((value >> 24) & 0xff));
}
void appendS32(std::vector<uint8_t>& out, int32_t value) { appendU32(out, static_cast<uint32_t>(value)); }

std::vector<uint8_t> bytesU16(std::initializer_list<uint16_t> values) {
    std::vector<uint8_t> out; out.reserve(values.size() * 2);
    for (auto value : values) appendU16(out, value);
    return out;
}
std::vector<uint8_t> bytesU32(std::initializer_list<uint32_t> values) {
    std::vector<uint8_t> out; out.reserve(values.size() * 4);
    for (auto value : values) appendU32(out, value);
    return out;
}
std::vector<uint8_t> bytesRational(const std::vector<std::pair<int32_t,int32_t>>& values, bool signedValues) {
    std::vector<uint8_t> out; out.reserve(values.size() * 8);
    for (const auto& value : values) {
        if (signedValues) {
            appendS32(out, value.first); appendS32(out, value.second);
        } else {
            appendU32(out, static_cast<uint32_t>(std::max(0, value.first)));
            appendU32(out, static_cast<uint32_t>(std::max(1, value.second)));
        }
    }
    return out;
}
std::vector<uint8_t> bytesAscii(const std::string& text) {
    std::vector<uint8_t> out(text.begin(), text.end()); out.push_back(0); return out;
}

struct TiffEntry {
    uint16_t tag = 0;
    uint16_t type = 0;
    uint32_t count = 0;
    std::vector<uint8_t> data;
    uint32_t externalOffset = 0;
};

TiffEntry entry(uint16_t tag, uint16_t type, uint32_t count, std::vector<uint8_t> data) {
    return {tag, type, count, std::move(data), 0};
}

std::vector<uint8_t> matrixRational(const std::array<double,9>& matrix) {
    std::vector<std::pair<int32_t,int32_t>> values;
    values.reserve(9);
    constexpr int32_t denominator = 1000000;
    for (double value : matrix) values.emplace_back(static_cast<int32_t>(std::llround(value * denominator)), denominator);
    return bytesRational(values, true);
}

bool writeAll(int fd, const void* data, size_t bytes) {
    const uint8_t* p = static_cast<const uint8_t*>(data);
    while (bytes > 0) {
        const ssize_t written = ::write(fd, p, bytes);
        if (written <= 0) return false;
        p += written;
        bytes -= static_cast<size_t>(written);
    }
    return true;
}

bool writeLinearDng(const std::string& path, const RgbImage& image, const std::string& cameraModel) {
    if (image.width <= 0 || image.height <= 0 || image.pixels.size() != static_cast<size_t>(image.width) * image.height * 3) return false;

    std::vector<uint16_t> rgb16(image.pixels.size());
    for (size_t i = 0; i < image.pixels.size(); ++i) rgb16[i] = static_cast<uint16_t>(clampi(static_cast<int>(image.pixels[i] * 65535.f + 0.5f), 0, 65535));

    static constexpr std::array<double,9> xyzD65ToSrgb = {
        3.2404542, -1.5371385, -0.4985314,
       -0.9692660,  1.8760108,  0.0415560,
        0.0556434, -0.2040259,  1.0572252,
    };
    static constexpr std::array<double,9> srgbToXyzD50 = {
        0.4360747, 0.3850649, 0.1430804,
        0.2225045, 0.7168786, 0.0606169,
        0.0139322, 0.0971045, 0.7141733,
    };

    std::vector<TiffEntry> entries;
    entries.push_back(entry(254, 4, 1, bytesU32({0}))); // NewSubFileType
    entries.push_back(entry(256, 4, 1, bytesU32({static_cast<uint32_t>(image.width)})));
    entries.push_back(entry(257, 4, 1, bytesU32({static_cast<uint32_t>(image.height)})));
    entries.push_back(entry(258, 3, 3, bytesU16({16,16,16})));
    entries.push_back(entry(259, 3, 1, bytesU16({1}))); // uncompressed
    entries.push_back(entry(262, 3, 1, bytesU16({34892}))); // LinearRaw
    entries.push_back(entry(271, 2, static_cast<uint32_t>(cameraModel.size()+1), bytesAscii(cameraModel)));
    entries.push_back(entry(272, 2, static_cast<uint32_t>(cameraModel.size()+1), bytesAscii(cameraModel)));
    entries.push_back(entry(274, 3, 1, bytesU16({1})));
    entries.push_back(entry(277, 3, 1, bytesU16({3})));
    entries.push_back(entry(278, 4, 1, bytesU32({static_cast<uint32_t>(image.height)})));
    entries.push_back(entry(279, 4, 1, bytesU32({static_cast<uint32_t>(rgb16.size() * sizeof(uint16_t))})));
    entries.push_back(entry(284, 3, 1, bytesU16({1})));
    entries.push_back(entry(305, 2, 18, bytesAscii("Camera Native C++")));
    entries.push_back(entry(339, 3, 3, bytesU16({1,1,1})));
    entries.push_back(entry(50706, 1, 4, {1,4,0,0}));
    entries.push_back(entry(50707, 1, 4, {1,4,0,0}));
    entries.push_back(entry(50708, 2, static_cast<uint32_t>(cameraModel.size()+1), bytesAscii(cameraModel)));
    entries.push_back(entry(50713, 3, 2, bytesU16({1,1})));
    entries.push_back(entry(50714, 5, 3, bytesRational({{0,1},{0,1},{0,1}}, false)));
    entries.push_back(entry(50717, 4, 3, bytesU32({65535,65535,65535})));
    entries.push_back(entry(50718, 5, 2, bytesRational({{1,1},{1,1}}, false)));
    entries.push_back(entry(50719, 5, 2, bytesRational({{0,1},{0,1}}, false)));
    entries.push_back(entry(50720, 5, 2, bytesRational({{image.width,1},{image.height,1}}, false)));
    entries.push_back(entry(50721, 10, 9, matrixRational(xyzD65ToSrgb)));
    entries.push_back(entry(50728, 5, 3, bytesRational({{1,1},{1,1},{1,1}}, false)));
    entries.push_back(entry(50730, 10, 1, bytesRational({{0,1}}, true)));
    entries.push_back(entry(50731, 5, 1, bytesRational({{1,1}}, false)));
    entries.push_back(entry(50732, 5, 1, bytesRational({{1,1}}, false)));
    entries.push_back(entry(50778, 3, 1, bytesU16({21}))); // D65
    entries.push_back(entry(50829, 4, 4, bytesU32({0,0,static_cast<uint32_t>(image.height),static_cast<uint32_t>(image.width)})));
    entries.push_back(entry(50936, 2, 20, bytesAscii("Camera Computational")));
    entries.push_back(entry(50964, 10, 9, matrixRational(srgbToXyzD50)));
    // StripOffsets; value filled after external metadata layout is known.
    entries.push_back(entry(273, 4, 1, bytesU32({0})));

    std::sort(entries.begin(), entries.end(), [](const TiffEntry& a, const TiffEntry& b) { return a.tag < b.tag; });
    const uint32_t ifdOffset = 8;
    const uint32_t ifdBytes = 2 + static_cast<uint32_t>(entries.size()) * 12 + 4;
    uint32_t extraOffset = ifdOffset + ifdBytes;
    for (auto& e : entries) {
        if (e.data.size() > 4) {
            if (extraOffset & 1u) ++extraOffset;
            e.externalOffset = extraOffset;
            extraOffset += static_cast<uint32_t>(e.data.size());
        }
    }
    if (extraOffset & 1u) ++extraOffset;
    const uint32_t pixelOffset = extraOffset;
    for (auto& e : entries) if (e.tag == 273) e.data = bytesU32({pixelOffset});

    std::vector<uint8_t> header;
    header.reserve(pixelOffset);
    header.push_back('I'); header.push_back('I');
    appendU16(header, 42);
    appendU32(header, ifdOffset);
    appendU16(header, static_cast<uint16_t>(entries.size()));
    for (const auto& e : entries) {
        appendU16(header, e.tag);
        appendU16(header, e.type);
        appendU32(header, e.count);
        if (e.data.size() <= 4) {
            header.insert(header.end(), e.data.begin(), e.data.end());
            while ((header.size() - (ifdOffset + 2)) % 12 != 0) header.push_back(0);
        } else {
            appendU32(header, e.externalOffset);
        }
    }
    appendU32(header, 0); // next IFD

    if (header.size() < ifdOffset + ifdBytes) header.resize(ifdOffset + ifdBytes, 0);
    for (const auto& e : entries) {
        if (e.data.size() <= 4) continue;
        if (header.size() < e.externalOffset) header.resize(e.externalOffset, 0);
        header.insert(header.end(), e.data.begin(), e.data.end());
    }
    if (header.size() < pixelOffset) header.resize(pixelOffset, 0);

    const int fd = ::open(path.c_str(), O_CREAT | O_TRUNC | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    const bool okHeader = writeAll(fd, header.data(), header.size());
    const bool okPixels = okHeader && writeAll(fd, rgb16.data(), rgb16.size() * sizeof(uint16_t));
    if (okPixels) fsync(fd);
    ::close(fd);
    if (!okPixels) unlink(path.c_str());
    return okPixels;
}

std::vector<std::string> javaStrings(JNIEnv* env, jobjectArray array) {
    const jsize count = env->GetArrayLength(array);
    std::vector<std::string> out; out.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        const char* chars = env->GetStringUTFChars(value, nullptr);
        out.emplace_back(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }
    return out;
}

std::string javaString(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jlongArray fail(JNIEnv* env, int code) {
    jlong values[5] = {0,0,0,0,code};
    auto out = env->NewLongArray(5);
    env->SetLongArrayRegion(out, 0, 5, values);
    return out;
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
    jintArray whiteLevels,
    jfloatArray awbGains,
    jfloatArray colorTransforms,
    jint width,
    jint height,
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
    jstring cameraModel,
    jstring outputPath
) {
    if (!inputPaths || width <= 0 || height <= 0 || cfaArrangement < 0 || cfaArrangement > 3) return fail(env, -1);
    const auto paths = javaStrings(env, inputPaths);
    const int count = static_cast<int>(paths.size());
    if (count <= 0) return fail(env, -2);
    if (
        env->GetArrayLength(exposureTimesNs) != count ||
        env->GetArrayLength(sensitivities) != count ||
        env->GetArrayLength(blackLevels) != count * 4 ||
        env->GetArrayLength(whiteLevels) != count ||
        env->GetArrayLength(awbGains) != count * 4 ||
        env->GetArrayLength(colorTransforms) != count * 9
    ) return fail(env, -3);

    std::vector<jlong> exposures(count);
    std::vector<jint> isos(count), whites(count), blacks(count * 4);
    std::vector<jfloat> gains(count * 4), transforms(count * 9);
    env->GetLongArrayRegion(exposureTimesNs, 0, count, exposures.data());
    env->GetIntArrayRegion(sensitivities, 0, count, isos.data());
    env->GetIntArrayRegion(blackLevels, 0, count * 4, blacks.data());
    env->GetIntArrayRegion(whiteLevels, 0, count, whites.data());
    env->GetFloatArrayRegion(awbGains, 0, count * 4, gains.data());
    env->GetFloatArrayRegion(colorTransforms, 0, count * 9, transforms.data());

    const size_t expectedBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    std::vector<Frame> frames; frames.reserve(count);
    for (int i = 0; i < count; ++i) {
        Frame frame;
        frame.exposureNs = std::max<int64_t>(1, exposures[i]);
        frame.iso = std::max(1, static_cast<int>(isos[i]));
        frame.white = std::max(1, static_cast<int>(whites[i]));
        for (int c = 0; c < 4; ++c) {
            frame.black[c] = std::max(0, static_cast<int>(blacks[i * 4 + c]));
            frame.gains[c] = std::max(0.0001f, static_cast<float>(gains[i * 4 + c]));
        }
        for (int e = 0; e < 9; ++e) frame.transform[e] = static_cast<float>(transforms[i * 9 + e]);
        if (!frame.raw.openFile(paths[i], expectedBytes)) return fail(env, -10 - i);
        frames.emplace_back(std::move(frame));
    }

    const int referenceIndex = chooseReference(frames, width, height);
    const long double referenceProduct = static_cast<long double>(frames[referenceIndex].exposureNs) * frames[referenceIndex].iso;
    for (auto& frame : frames) {
        const long double product = std::max<long double>(1.0L, static_cast<long double>(frame.exposureNs) * frame.iso);
        frame.exposureScale = static_cast<float>(std::clamp(referenceProduct / product, 0.0625L, 32.0L));
    }

    std::vector<Align> alignments(count);
    alignments[referenceIndex] = {0,0,1.f};
    for (int i = 0; i < count; ++i) if (i != referenceIndex) alignments[i] = estimateAlignment(frames[referenceIndex], frames[i], width, height);

    const Frame& reference = frames[referenceIndex];
    std::vector<float> fused(static_cast<size_t>(width) * height);
    int64_t accepted = 0, rejected = 0;
    const float dn = clampf(denoise, 0.f, 2.f);
    const bool hdr = hdrEnabled == JNI_TRUE;
    const float hdrAmount = hdr ? clampf(hdrStrength, 0.f, 2.f) : 0.f;

    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const size_t index = static_cast<size_t>(y) * width + x;
            const float refValue = normalize(reference, reference.raw.data[index], x, y);
            float sum = refValue * 4.f;
            float weights = 4.f;
            for (int i = 0; i < count; ++i) {
                if (i == referenceIndex) continue;
                const auto& alignment = alignments[i];
                if (alignment.confidence < 0.12f) { ++rejected; continue; }
                const int sx = x + alignment.dx;
                const int sy = y + alignment.dy;
                if (sx < 0 || sy < 0 || sx >= width || sy >= height) { ++rejected; continue; }
                const Frame& sourceFrame = frames[i];
                const float sourceRaw = normalize(sourceFrame, sourceFrame.raw.data[static_cast<size_t>(sy) * width + sx], sx, sy);
                if (sourceRaw >= 0.999f) { ++rejected; continue; }
                const float source = sourceRaw * sourceFrame.exposureScale;
                const float delta = std::abs(source - refValue);
                const bool bracket = std::abs(sourceFrame.exposureScale - 1.f) > 0.125f;
                const bool recoverHighlight = hdr && bracket && refValue > 0.88f && sourceRaw < 0.93f;
                const float threshold = 0.022f + dn * 0.055f + refValue * 0.025f + (recoverHighlight ? 0.11f * hdrAmount : 0.f);
                if (!recoverHighlight && delta > threshold) { ++rejected; continue; }
                float weight = bracket ? (hdr ? 2.f : 1.f) : 3.f;
                if (alignment.confidence < 0.4f) weight *= 0.5f;
                if (delta > threshold * 0.5f) weight *= 0.5f;
                sum += source * std::max(0.5f, weight);
                weights += std::max(0.5f, weight);
                ++accepted;
            }
            fused[index] = clampf(sum / std::max(0.5f, weights), 0.f, 2.f);
        }
    }

    auto rgb = demosaic(fused, width, height, cfaArrangement, reference);
    applyColorControls(rgb, hdrAmount, highlightProtection, shadowRecovery, saturation);
    applySharpness(rgb, sharpness);
    rgb = upscaleRgb(rgb, clampi(upscaleFactor, 1, 4));
    rgb = cropAspect(std::move(rgb), targetAspect);

    const std::string model = javaString(env, cameraModel);
    const std::string output = javaString(env, outputPath);
    if (!writeLinearDng(output, rgb, model.empty() ? "Camera" : model)) return fail(env, -30);

    std::vector<jlong> metrics;
    metrics.reserve(5 + count * 3);
    metrics.push_back(rgb.width);
    metrics.push_back(rgb.height);
    metrics.push_back(accepted);
    metrics.push_back(rejected);
    metrics.push_back(referenceIndex);
    for (const auto& alignment : alignments) {
        metrics.push_back(alignment.dx);
        metrics.push_back(alignment.dy);
        metrics.push_back(static_cast<jlong>(clampf(alignment.confidence, 0.f, 1.f) * 10000.f + 0.5f));
    }
    auto result = env->NewLongArray(static_cast<jsize>(metrics.size()));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(metrics.size()), metrics.data());
    return result;
}
