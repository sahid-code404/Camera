#include <jni.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <media/NdkImage.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <set>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr const char* TAG = "CameraNdkDiscovery";

struct SizePair {
    int width = 0;
    int height = 0;
    int format = 0;
    long long area() const { return static_cast<long long>(width) * height; }
};

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                        << static_cast<int>(static_cast<unsigned char>(c)) << std::dec;
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

bool getEntry(const ACameraMetadata* metadata, uint32_t tag, ACameraMetadata_const_entry* entry) {
    return metadata != nullptr && ACameraMetadata_getConstEntry(metadata, tag, entry) == ACAMERA_OK;
}

std::string hardwareLevelName(uint8_t value) {
    switch (value) {
        case ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: return "LIMITED";
        case ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_FULL: return "FULL";
        case ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: return "LEGACY";
        case ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_3: return "LEVEL_3";
        case ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: return "EXTERNAL";
        default: return "UNKNOWN";
    }
}

bool hasCapability(const ACameraMetadata* metadata, uint8_t wanted) {
    ACameraMetadata_const_entry entry{};
    if (!getEntry(metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES, &entry)) return false;
    for (uint32_t i = 0; i < entry.count; ++i) {
        if (entry.data.u8[i] == wanted) return true;
    }
    return false;
}

std::vector<SizePair> streamSizes(const ACameraMetadata* metadata, int wantedFormat) {
    std::vector<SizePair> result;
    ACameraMetadata_const_entry entry{};
    if (!getEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry)) return result;
    for (uint32_t i = 0; i + 3 < entry.count; i += 4) {
        const int format = entry.data.i32[i];
        const int width = entry.data.i32[i + 1];
        const int height = entry.data.i32[i + 2];
        const int input = entry.data.i32[i + 3];
        if (input == 0 && format == wantedFormat && width > 0 && height > 0) {
            result.push_back({width, height, format});
        }
    }
    std::sort(result.begin(), result.end(), [](const SizePair& a, const SizePair& b) {
        return a.area() > b.area();
    });
    result.erase(std::unique(result.begin(), result.end(), [](const SizePair& a, const SizePair& b) {
        return a.width == b.width && a.height == b.height && a.format == b.format;
    }), result.end());
    return result;
}

SizePair chooseRaw(const ACameraMetadata* metadata) {
    // Prefer unpacked RAW16. RAW12 and RAW10 are also genuine Bayer sensor outputs and are unpacked
    // into 16-bit samples by the native capture path before computational processing.
    for (const int format : {AIMAGE_FORMAT_RAW10, AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12}) {
        auto sizes = streamSizes(metadata, format);
        if (!sizes.empty()) return sizes.front();
    }
    return {};
}

SizePair choosePreview(const ACameraMetadata* metadata, const SizePair& raw) {
    auto previews = streamSizes(metadata, AIMAGE_FORMAT_PRIVATE);
    if (previews.empty()) return {};
    const double rawRatio = raw.height > 0 ? static_cast<double>(raw.width) / raw.height : 4.0 / 3.0;
    SizePair best{};
    long long bestArea = 0;
    for (const auto& size : previews) {
        const long long area = size.area();
        if (area > 1920LL * 1080LL) continue;
        const double ratio = static_cast<double>(size.width) / size.height;
        if (std::abs(ratio - rawRatio) > 0.08) continue;
        if (area > bestArea) {
            best = size;
            bestArea = area;
        }
    }
    if (bestArea == 0) {
        for (const auto& size : previews) {
            if (size.area() <= 1920LL * 1080LL && size.area() > bestArea) {
                best = size;
                bestArea = size.area();
            }
        }
    }
    return bestArea > 0 ? best : previews.back();
}

void appendIntArray(std::ostringstream& out, const int* values, int count) {
    out << '[';
    for (int i = 0; i < count; ++i) {
        if (i) out << ',';
        out << values[i];
    }
    out << ']';
}

std::string describeCamera(ACameraManager* manager, const char* cameraId) {
    ACameraMetadata* metadataRaw = nullptr;
    if (ACameraManager_getCameraCharacteristics(manager, cameraId, &metadataRaw) != ACAMERA_OK || metadataRaw == nullptr) {
        return {};
    }
    std::unique_ptr<ACameraMetadata, decltype(&ACameraMetadata_free)> metadata(metadataRaw, ACameraMetadata_free);

    const bool rawCapabilityAdvertised = hasCapability(metadata.get(), ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_RAW);
    const SizePair raw = chooseRaw(metadata.get());
    // Stream configuration is the final metadata-level evidence. Some vendor/physical camera blocks
    // publish a legitimate RAW stream while omitting the RAW capability flag, so do not discard a
    // real RAW10/12/16 stream solely because that redundant bit is missing.
    if (raw.width <= 0 || raw.height <= 0) return {};

    const SizePair preview = choosePreview(metadata.get(), raw);
    if (preview.width <= 0 || preview.height <= 0) return {};

    int facing = -1;
    int hardware = -1;
    float focal = 0.0f;
    float sensorWidth = 0.0f;
    float sensorHeight = 0.0f;
    int activeWidth = raw.width;
    int activeHeight = raw.height;
    int cfa = -1;
    int white = raw.format == AIMAGE_FORMAT_RAW10 ? 1023 :
        (raw.format == AIMAGE_FORMAT_RAW12 ? 4095 : 65535);
    int black[4] = {0, 0, 0, 0};
    int isoMin = 0;
    int isoMax = 0;
    long long exposureMin = 0;
    long long exposureMax = 0;

    ACameraMetadata_const_entry entry{};
    if (getEntry(metadata.get(), ACAMERA_LENS_FACING, &entry) && entry.count > 0) facing = entry.data.u8[0];
    if (getEntry(metadata.get(), ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL, &entry) && entry.count > 0) hardware = entry.data.u8[0];
    if (getEntry(metadata.get(), ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS, &entry) && entry.count > 0) focal = entry.data.f[0];
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_PHYSICAL_SIZE, &entry) && entry.count >= 2) {
        sensorWidth = entry.data.f[0];
        sensorHeight = entry.data.f[1];
    }
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_ACTIVE_ARRAY_SIZE, &entry) && entry.count >= 4) {
        activeWidth = entry.data.i32[2] - entry.data.i32[0];
        activeHeight = entry.data.i32[3] - entry.data.i32[1];
    }
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, &entry) && entry.count > 0) cfa = entry.data.u8[0];
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_WHITE_LEVEL, &entry) && entry.count > 0) white = entry.data.i32[0];
    if (getEntry(metadata.get(), ACAMERA_SENSOR_BLACK_LEVEL_PATTERN, &entry) && entry.count >= 4) {
        for (int i = 0; i < 4; ++i) black[i] = entry.data.i32[i];
    }
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, &entry) && entry.count >= 2) {
        isoMin = entry.data.i32[0];
        isoMax = entry.data.i32[1];
    }
    if (getEntry(metadata.get(), ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE, &entry) && entry.count >= 2) {
        exposureMin = entry.data.i64[0];
        exposureMax = entry.data.i64[1];
    }

    const bool manual = hasCapability(metadata.get(), ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    const bool burst = hasCapability(metadata.get(), ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE);

    std::ostringstream out;
    out << '{'
        << "\"id\":\"" << jsonEscape(cameraId) << "\","
        << "\"facing\":" << facing << ','
        << "\"hardware\":\"" << hardwareLevelName(static_cast<uint8_t>(hardware)) << "\","
        << "\"rawCapabilityAdvertised\":" << (rawCapabilityAdvertised ? "true" : "false") << ','
        << "\"rawFormat\":" << raw.format << ','
        << "\"rawWidth\":" << raw.width << ','
        << "\"rawHeight\":" << raw.height << ','
        << "\"previewWidth\":" << preview.width << ','
        << "\"previewHeight\":" << preview.height << ','
        << "\"focal\":" << focal << ','
        << "\"sensorWidth\":" << sensorWidth << ','
        << "\"sensorHeight\":" << sensorHeight << ','
        << "\"activeWidth\":" << activeWidth << ','
        << "\"activeHeight\":" << activeHeight << ','
        << "\"cfa\":" << cfa << ','
        << "\"white\":" << white << ','
        << "\"black\":";
    appendIntArray(out, black, 4);
    out << ','
        << "\"isoMin\":" << isoMin << ','
        << "\"isoMax\":" << isoMax << ','
        << "\"exposureMin\":" << exposureMin << ','
        << "\"exposureMax\":" << exposureMax << ','
        << "\"manual\":" << (manual ? "true" : "false") << ','
        << "\"burst\":" << (burst ? "true" : "false")
        << '}';
    return out.str();
}

std::string enumerateJson(bool deepScan) {
    ACameraManager* managerRaw = ACameraManager_create();
    if (managerRaw == nullptr) return "[]";
    std::unique_ptr<ACameraManager, decltype(&ACameraManager_delete)> manager(managerRaw, ACameraManager_delete);

    ACameraIdList* idListRaw = nullptr;
    if (ACameraManager_getCameraIdList(manager.get(), &idListRaw) != ACAMERA_OK || idListRaw == nullptr) {
        return "[]";
    }
    std::unique_ptr<ACameraIdList, void(*)(ACameraIdList*)> idList(idListRaw, ACameraManager_deleteCameraIdList);

    std::vector<std::string> candidates;
    std::set<std::string> seen;
    int maxNumeric = -1;
    for (int i = 0; i < idList->numCameras; ++i) {
        const char* id = idList->cameraIds[i];
        if (id == nullptr || *id == '\0') continue;
        const std::string value(id);
        if (seen.insert(value).second) candidates.push_back(value);
        char* endPtr = nullptr;
        const long numeric = std::strtol(value.c_str(), &endPtr, 10);
        if (endPtr != value.c_str() && endPtr != nullptr && *endPtr == '\0' && numeric >= 0 && numeric <= 128) {
            maxNumeric = std::max(maxNumeric, static_cast<int>(numeric));
        }
    }

    if (deepScan) {
        // This pass is deliberately metadata-only: no camera is opened. Scan a bounded numeric
        // namespace so Qualcomm/vendor IDs filtered from both Java and the advertised NDK list can
        // still be found without device-specific hard-coded IDs. It runs after the first UI render.
        const int upper = std::min(31, std::max(11, maxNumeric + 8));
        for (int id = 0; id <= upper; ++id) {
            const std::string value = std::to_string(id);
            if (seen.insert(value).second) candidates.push_back(value);
        }
    }

    std::ostringstream out;
    out << '[';
    bool first = true;
    int rawCount = 0;
    for (const auto& id : candidates) {
        const std::string description = describeCamera(manager.get(), id.c_str());
        if (description.empty()) continue;
        if (!first) out << ',';
        first = false;
        ++rawCount;
        out << description;
    }
    out << ']';
    __android_log_print(
        ANDROID_LOG_INFO,
        TAG,
        "NDK discovery advertised=%d candidates=%zu deep=%d retainedRAW=%d",
        idList->numCameras,
        candidates.size(),
        deepScan ? 1 : 0,
        rawCount);
    return out.str();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeEnumerateJson(JNIEnv* env, jobject, jboolean deepScan) {
    const std::string json = enumerateJson(deepScan == JNI_TRUE);
    return env->NewStringUTF(json.c_str());
}
