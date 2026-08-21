#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr const char* TAG = "CameraNativeSession";
constexpr int kRawReaderBuffers = 10;
constexpr int kCaptureTimeoutSeconds = 16;

struct RawConfig {
    int width = 0;
    int height = 0;
    int format = 0;
    int cfa = -1;
    int whiteLevel = 65535;
    int blackLevels[4] = {0, 0, 0, 0};
    int isoMin = 50;
    int isoMax = 6400;
    int64_t exposureMinNs = 1000;
    int64_t exposureMaxNs = 1000000000LL;
    bool manualSensor = false;
};

struct CaptureMeta {
    int64_t timestampNs = 0;
    int64_t exposureTimeNs = 10000000LL;
    int iso = 100;
    float awb[4] = {1.f, 1.f, 1.f, 1.f};
    float color[9] = {
        1.f, 0.f, 0.f,
        0.f, 1.f, 0.f,
        0.f, 0.f, 1.f,
    };
};

struct StagedImage {
    int64_t timestampNs = 0;
    int width = 0;
    int height = 0;
    std::string path;
};

enum class SessionStrategy {
    NONE,
    CONCURRENT_PREVIEW_RAW,
    SWITCH_PREVIEW_RAW,
};

const char* strategyName(SessionStrategy strategy) {
    switch (strategy) {
        case SessionStrategy::CONCURRENT_PREVIEW_RAW: return "concurrent";
        case SessionStrategy::SWITCH_PREVIEW_RAW: return "switch";
        default: return "none";
    }
}

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

bool hasCapability(const ACameraMetadata* metadata, uint8_t wanted) {
    ACameraMetadata_const_entry entry{};
    if (!getEntry(metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES, &entry)) return false;
    for (uint32_t i = 0; i < entry.count; ++i) {
        if (entry.data.u8[i] == wanted) return true;
    }
    return false;
}

bool chooseRawConfig(const ACameraMetadata* metadata, RawConfig* config) {
    if (metadata == nullptr || config == nullptr) return false;

    struct Candidate { int width; int height; int format; int64_t area; };
    Candidate best{0, 0, 0, 0};
    ACameraMetadata_const_entry streams{};
    if (!getEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &streams)) return false;

    // Stream presence is stronger evidence than the redundant RAW capability bit on some vendor
    // physical-camera blocks. Prefer unpacked RAW16, then RAW12, then RAW10.
    for (const int wanted : {AIMAGE_FORMAT_RAW10, AIMAGE_FORMAT_RAW16, AIMAGE_FORMAT_RAW12}) {
        Candidate formatBest{0, 0, 0, 0};
        for (uint32_t i = 0; i + 3 < streams.count; i += 4) {
            const int format = streams.data.i32[i];
            const int width = streams.data.i32[i + 1];
            const int height = streams.data.i32[i + 2];
            const int input = streams.data.i32[i + 3];
            if (input != 0 || format != wanted || width <= 0 || height <= 0) continue;
            const int64_t area = static_cast<int64_t>(width) * height;
            if (area > formatBest.area) formatBest = {width, height, format, area};
        }
        if (formatBest.area > 0) {
            best = formatBest;
            break;
        }
    }
    if (best.area <= 0) return false;

    config->width = best.width;
    config->height = best.height;
    config->format = best.format;
    config->whiteLevel = best.format == AIMAGE_FORMAT_RAW10 ? 1023 :
        (best.format == AIMAGE_FORMAT_RAW12 ? 4095 : 65535);

    ACameraMetadata_const_entry entry{};
    if (getEntry(metadata, ACAMERA_SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, &entry) && entry.count > 0) {
        config->cfa = entry.data.u8[0];
    }
    if (getEntry(metadata, ACAMERA_SENSOR_INFO_WHITE_LEVEL, &entry) && entry.count > 0) {
        config->whiteLevel = std::max(1, entry.data.i32[0]);
    }
    if (getEntry(metadata, ACAMERA_SENSOR_BLACK_LEVEL_PATTERN, &entry) && entry.count >= 4) {
        for (int i = 0; i < 4; ++i) config->blackLevels[i] = std::max(0, entry.data.i32[i]);
    }
    if (getEntry(metadata, ACAMERA_SENSOR_INFO_SENSITIVITY_RANGE, &entry) && entry.count >= 2) {
        config->isoMin = std::max(1, entry.data.i32[0]);
        config->isoMax = std::max(config->isoMin, entry.data.i32[1]);
    }
    if (getEntry(metadata, ACAMERA_SENSOR_INFO_EXPOSURE_TIME_RANGE, &entry) && entry.count >= 2) {
        config->exposureMinNs = std::max<int64_t>(1, entry.data.i64[0]);
        config->exposureMaxNs = std::max(config->exposureMinNs, entry.data.i64[1]);
    }
    config->manualSensor = hasCapability(metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR);
    return true;
}

CaptureMeta readCaptureMeta(const ACameraMetadata* metadata, const CaptureMeta& fallback) {
    CaptureMeta result = fallback;
    ACameraMetadata_const_entry entry{};
    if (getEntry(metadata, ACAMERA_SENSOR_TIMESTAMP, &entry) && entry.count > 0) {
        result.timestampNs = entry.data.i64[0];
    }
    if (getEntry(metadata, ACAMERA_SENSOR_EXPOSURE_TIME, &entry) && entry.count > 0) {
        result.exposureTimeNs = std::max<int64_t>(1, entry.data.i64[0]);
    }
    if (getEntry(metadata, ACAMERA_SENSOR_SENSITIVITY, &entry) && entry.count > 0) {
        result.iso = std::max(1, entry.data.i32[0]);
    }
    if (getEntry(metadata, ACAMERA_COLOR_CORRECTION_GAINS, &entry) && entry.count >= 4) {
        for (int i = 0; i < 4; ++i) {
            const float value = entry.data.f[i];
            result.awb[i] = std::isfinite(value) && value > 0.f ? value : fallback.awb[i];
        }
    }
    if (getEntry(metadata, ACAMERA_COLOR_CORRECTION_TRANSFORM, &entry) && entry.count >= 9) {
        for (int i = 0; i < 9; ++i) {
            const auto rational = entry.data.r[i];
            if (rational.denominator != 0) {
                const float value = static_cast<float>(rational.numerator) /
                    static_cast<float>(rational.denominator);
                if (std::isfinite(value)) result.color[i] = value;
            }
        }
    }
    return result;
}

int adaptiveFrameCount(int requestedFrames, const CaptureMeta& base) {
    if (requestedFrames > 0) return std::max(1, std::min(requestedFrames, 8));
    const double exposureMs = std::max(0.001, static_cast<double>(base.exposureTimeNs) / 1.0e6);
    const double isoScale = std::max(1.0, static_cast<double>(std::max(1, base.iso)) / 100.0);
    const double lightCost = exposureMs * isoScale;
    if (lightCost >= 140.0) return 8;
    if (lightCost >= 70.0) return 7;
    if (lightCost >= 32.0) return 6;
    if (lightCost >= 12.0) return 5;
    return 4;
}

class NativeCameraSession {
public:
    NativeCameraSession() = default;
    ~NativeCameraSession() { stop(); }

    std::string start(JNIEnv* env, const std::string& cameraId, jobject surface) {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
        stopLocked();
        cameraId_ = cameraId;
        alive_.store(true);

        manager_ = ACameraManager_create();
        if (manager_ == nullptr) return failStartLocked("Unable to create NDK camera manager");

        if (ACameraManager_getCameraCharacteristics(manager_, cameraId.c_str(), &characteristics_) != ACAMERA_OK ||
            characteristics_ == nullptr) {
            return failStartLocked("NDK camera characteristics unavailable");
        }
        if (!chooseRawConfig(characteristics_, &rawConfig_)) {
            return failStartLocked("NDK camera has no genuine RAW16/RAW12/RAW10 output");
        }
        if (rawConfig_.cfa < 0 || rawConfig_.cfa > 3) {
            return failStartLocked("NDK RAW camera has unsupported Bayer CFA");
        }

        previewWindow_ = ANativeWindow_fromSurface(env, surface);
        if (previewWindow_ == nullptr) return failStartLocked("Unable to acquire preview surface");

        ACameraDevice_StateCallbacks deviceCallbacks{};
        deviceCallbacks.context = this;
        deviceCallbacks.onDisconnected = onDeviceDisconnected;
        deviceCallbacks.onError = onDeviceError;
        if (ACameraManager_openCamera(manager_, cameraId.c_str(), &deviceCallbacks, &device_) != ACAMERA_OK ||
            device_ == nullptr) {
            return failStartLocked("NDK camera open failed");
        }

        if (AImageReader_new(
                rawConfig_.width,
                rawConfig_.height,
                rawConfig_.format,
                kRawReaderBuffers,
                &rawReader_) != AMEDIA_OK || rawReader_ == nullptr) {
            return failStartLocked("Unable to create NDK RAW image reader");
        }
        AImageReader_ImageListener imageListener{};
        imageListener.context = this;
        imageListener.onImageAvailable = onImageAvailable;
        if (AImageReader_setImageListener(rawReader_, &imageListener) != AMEDIA_OK) {
            return failStartLocked("Unable to attach RAW image listener");
        }
        if (AImageReader_getWindow(rawReader_, &rawWindow_) != AMEDIA_OK || rawWindow_ == nullptr) {
            return failStartLocked("Unable to acquire RAW reader window");
        }

        if (ACaptureSessionOutput_create(previewWindow_, &previewOutput_) != ACAMERA_OK || previewOutput_ == nullptr) {
            return failStartLocked("Unable to create NDK preview output");
        }
        if (ACameraOutputTarget_create(previewWindow_, &previewTarget_) != ACAMERA_OK || previewTarget_ == nullptr) {
            return failStartLocked("Unable to create NDK preview target");
        }
        if (ACaptureSessionOutput_create(rawWindow_, &rawOutput_) != ACAMERA_OK || rawOutput_ == nullptr) {
            return failStartLocked("Unable to create NDK RAW output");
        }
        if (ACameraOutputTarget_create(rawWindow_, &rawTarget_) != ACAMERA_OK || rawTarget_ == nullptr) {
            return failStartLocked("Unable to create NDK RAW target");
        }

        if (ACameraDevice_createCaptureRequest(device_, TEMPLATE_PREVIEW, &previewRequest_) != ACAMERA_OK ||
            previewRequest_ == nullptr) {
            return failStartLocked("Unable to create NDK preview request");
        }
        if (ACaptureRequest_addTarget(previewRequest_, previewTarget_) != ACAMERA_OK) {
            return failStartLocked("Unable to target NDK preview surface");
        }
        configureAutomaticRequest(previewRequest_, false);
        initializeCallbacks();

        // Best path: keep preview + RAW outputs configured together. Some OEM auxiliary cameras
        // reject that combination even though each output is valid independently. In that case we
        // keep a preview-only session and switch briefly to a RAW-only session on shutter.
        if (rebuildSessionLocked(true, true)) {
            strategy_ = SessionStrategy::CONCURRENT_PREVIEW_RAW;
        } else if (rebuildSessionLocked(true, false)) {
            strategy_ = SessionStrategy::SWITCH_PREVIEW_RAW;
        } else {
            return failStartLocked("NDK camera rejected both concurrent and preview-only sessions");
        }

        if (!startPreviewRepeatingLocked()) {
            return failStartLocked("Unable to start NDK camera preview");
        }

        __android_log_print(
            ANDROID_LOG_INFO,
            TAG,
            "Opened NDK camera %s RAW=%dx%d format=%d strategy=%s",
            cameraId.c_str(), rawConfig_.width, rawConfig_.height, rawConfig_.format,
            strategyName(strategy_));
        return {};
    }

    void stop() {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
        stopLocked();
    }

    std::string captureBurst(const std::string& cacheDir, int requestedFrames, float hdrStrength) {
        std::unique_lock<std::mutex> lifecycleLock(lifecycleMutex_);
        std::unique_lock<std::mutex> captureLock(captureMutex_);
        if (!alive_.load() || device_ == nullptr || rawTarget_ == nullptr || strategy_ == SessionStrategy::NONE) {
            return errorJson("NDK camera session is not active");
        }
        if (captureActive_) return errorJson("NDK RAW capture already in progress");

        CaptureMeta base;
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            base = lastPreviewMeta_;
        }
        base.iso = std::max(rawConfig_.isoMin, std::min(base.iso, rawConfig_.isoMax));
        base.exposureTimeNs = std::max(rawConfig_.exposureMinNs,
            std::min(base.exposureTimeNs, rawConfig_.exposureMaxNs));
        const int frameCount = adaptiveFrameCount(requestedFrames, base);
        const RawConfig captureConfig = rawConfig_;
        const SessionStrategy captureStrategy = strategy_;

        captureActive_ = true;
        captureError_.clear();
        captureSequenceDone_ = false;
        expectedFrames_ = frameCount;
        captureCacheDir_ = cacheDir;
        capturedImages_.clear();
        capturedMetadata_.clear();

        if (captureStrategy == SessionStrategy::SWITCH_PREVIEW_RAW) {
            if (!rebuildSessionLocked(false, true)) {
                resetCaptureLocked();
                const bool restored = rebuildSessionLocked(true, false) && startPreviewRepeatingLocked();
                return errorJson(restored
                    ? "NDK camera rejected RAW-only capture session"
                    : "NDK camera rejected RAW-only session and preview could not be restored");
            }
        } else if (captureSession_ == nullptr) {
            resetCaptureLocked();
            return errorJson("NDK concurrent RAW session is unavailable");
        }

        std::vector<ACaptureRequest*> requests;
        requests.reserve(frameCount);
        const float strength = std::max(0.f, std::min(hdrStrength, 2.f));
        for (int index = 0; index < frameCount; ++index) {
            ACaptureRequest* request = nullptr;
            if (ACameraDevice_createCaptureRequest(device_, TEMPLATE_STILL_CAPTURE, &request) != ACAMERA_OK ||
                request == nullptr) {
                freeRequests(requests);
                resetCaptureLocked();
                if (captureStrategy == SessionStrategy::SWITCH_PREVIEW_RAW) {
                    rebuildSessionLocked(true, false);
                    startPreviewRepeatingLocked();
                }
                return errorJson("Unable to create NDK RAW still request");
            }
            if (ACaptureRequest_addTarget(request, rawTarget_) != ACAMERA_OK) {
                ACaptureRequest_free(request);
                freeRequests(requests);
                resetCaptureLocked();
                if (captureStrategy == SessionStrategy::SWITCH_PREVIEW_RAW) {
                    rebuildSessionLocked(true, false);
                    startPreviewRepeatingLocked();
                }
                return errorJson("Unable to target NDK RAW output");
            }

            if (captureConfig.manualSensor) {
                // Two highlight-protection exposures, then base exposures. Extra low-light frames
                // are base exposures so fusion gains SNR without darkening every sample.
                float ev = 0.f;
                if (index == 0) ev = -2.f * strength;
                else if (index == 1) ev = -1.f * strength;
                const double scale = std::pow(2.0, static_cast<double>(ev));
                int64_t exposure = static_cast<int64_t>(static_cast<double>(base.exposureTimeNs) * scale);
                exposure = std::max(captureConfig.exposureMinNs, std::min(exposure, captureConfig.exposureMaxNs));
                configureManualRequest(request, base.iso, exposure, captureConfig);
            } else {
                configureAutomaticRequest(request, true);
            }
            requests.push_back(request);
        }

        int sequenceId = 0;
        const camera_status_t submit = ACameraCaptureSession_capture(
            captureSession_, &stillCallbacks_, static_cast<int>(requests.size()), requests.data(), &sequenceId);
        if (submit != ACAMERA_OK) {
            freeRequests(requests);
            resetCaptureLocked();
            if (captureStrategy == SessionStrategy::SWITCH_PREVIEW_RAW) {
                rebuildSessionLocked(true, false);
                startPreviewRepeatingLocked();
            }
            return errorJson("NDK RAW burst submission failed");
        }

        // Device/session lifetime only has to be held through submission. stop() can now abort the
        // sequence safely and wake the condition variable if the app backgrounds or changes lens.
        lifecycleLock.unlock();

        const bool completed = captureCv_.wait_for(
            captureLock,
            std::chrono::seconds(kCaptureTimeoutSeconds),
            [&] {
                return !captureError_.empty() ||
                    (!captureActive_) ||
                    (static_cast<int>(capturedImages_.size()) >= expectedFrames_ && captureSequenceDone_);
            });

        freeRequests(requests);
        std::string failure;
        if (!completed) {
            failure = "NDK RAW burst timed out";
        } else if (!captureError_.empty()) {
            failure = captureError_;
        } else if (static_cast<int>(capturedImages_.size()) < frameCount) {
            failure = "NDK RAW burst returned too few frames";
        }

        std::vector<StagedImage> images;
        std::map<int64_t, CaptureMeta> metadata;
        if (failure.empty()) {
            std::sort(capturedImages_.begin(), capturedImages_.end(), [](const StagedImage& a, const StagedImage& b) {
                return a.timestampNs < b.timestampNs;
            });
            images = capturedImages_;
            metadata = capturedMetadata_;
        } else {
            images = capturedImages_;
        }
        resetCaptureLocked();
        captureLock.unlock();

        bool previewRestored = true;
        if (captureStrategy == SessionStrategy::SWITCH_PREVIEW_RAW && alive_.load()) {
            std::lock_guard<std::mutex> restoreLock(lifecycleMutex_);
            if (alive_.load()) {
                previewRestored = rebuildSessionLocked(true, false) && startPreviewRepeatingLocked();
            }
        }

        if (!failure.empty()) {
            deleteStaged(images);
            if (!previewRestored) failure += "; preview restoration failed";
            return errorJson(failure);
        }

        std::ostringstream json;
        json << "{\"ok\":true,\"cfa\":" << captureConfig.cfa
             << ",\"rawFormat\":" << captureConfig.format
             << ",\"frameCount\":" << frameCount
             << ",\"strategy\":\"" << strategyName(captureStrategy) << "\""
             << ",\"previewRestored\":" << (previewRestored ? "true" : "false")
             << ",\"frames\":[";
        for (size_t i = 0; i < images.size() && static_cast<int>(i) < frameCount; ++i) {
            if (i) json << ',';
            const auto& image = images[i];
            CaptureMeta meta = base;
            auto exact = metadata.find(image.timestampNs);
            if (exact != metadata.end()) {
                meta = exact->second;
            } else if (!metadata.empty()) {
                auto nearest = metadata.begin();
                int64_t bestDistance = std::llabs(nearest->first - image.timestampNs);
                for (auto it = std::next(metadata.begin()); it != metadata.end(); ++it) {
                    const int64_t distance = std::llabs(it->first - image.timestampNs);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        nearest = it;
                    }
                }
                meta = nearest->second;
            }
            json << '{'
                 << "\"path\":\"" << jsonEscape(image.path) << "\","
                 << "\"timestampNs\":" << image.timestampNs << ','
                 << "\"width\":" << image.width << ','
                 << "\"height\":" << image.height << ','
                 << "\"exposureTimeNs\":" << meta.exposureTimeNs << ','
                 << "\"iso\":" << meta.iso << ','
                 << "\"whiteLevel\":" << captureConfig.whiteLevel << ','
                 << "\"blackLevels\":[";
            for (int j = 0; j < 4; ++j) {
                if (j) json << ',';
                json << captureConfig.blackLevels[j];
            }
            json << "],\"awbGains\":[";
            for (int j = 0; j < 4; ++j) {
                if (j) json << ',';
                json << meta.awb[j];
            }
            json << "],\"colorTransform\":[";
            for (int j = 0; j < 9; ++j) {
                if (j) json << ',';
                json << meta.color[j];
            }
            json << "]}";
        }
        json << "]}";
        return json.str();
    }

private:
    void initializeCallbacks() {
        previewCallbacks_ = {};
        previewCallbacks_.context = this;
        previewCallbacks_.onCaptureStarted = onPreviewStarted;
        previewCallbacks_.onCaptureProgressed = onPreviewProgressed;
        previewCallbacks_.onCaptureCompleted = onPreviewCompleted;
        previewCallbacks_.onCaptureFailed = onPreviewFailed;
        previewCallbacks_.onCaptureSequenceCompleted = onPreviewSequenceCompleted;
        previewCallbacks_.onCaptureSequenceAborted = onPreviewSequenceAborted;
        previewCallbacks_.onCaptureBufferLost = onPreviewBufferLost;

        stillCallbacks_ = {};
        stillCallbacks_.context = this;
        stillCallbacks_.onCaptureStarted = onStillStarted;
        stillCallbacks_.onCaptureProgressed = onStillProgressed;
        stillCallbacks_.onCaptureCompleted = onStillCompleted;
        stillCallbacks_.onCaptureFailed = onStillFailed;
        stillCallbacks_.onCaptureSequenceCompleted = onStillSequenceCompleted;
        stillCallbacks_.onCaptureSequenceAborted = onStillSequenceAborted;
        stillCallbacks_.onCaptureBufferLost = onStillBufferLost;
    }

    bool rebuildSessionLocked(bool includePreview, bool includeRaw) {
        closeSessionLocked();
        if (device_ == nullptr) return false;
        if (ACaptureSessionOutputContainer_create(&outputContainer_) != ACAMERA_OK || outputContainer_ == nullptr) {
            return false;
        }
        containerHasPreview_ = false;
        containerHasRaw_ = false;

        if (includePreview) {
            if (previewOutput_ == nullptr ||
                ACaptureSessionOutputContainer_add(outputContainer_, previewOutput_) != ACAMERA_OK) {
                closeSessionLocked();
                return false;
            }
            containerHasPreview_ = true;
        }
        if (includeRaw) {
            if (rawOutput_ == nullptr ||
                ACaptureSessionOutputContainer_add(outputContainer_, rawOutput_) != ACAMERA_OK) {
                closeSessionLocked();
                return false;
            }
            containerHasRaw_ = true;
        }

        ACameraCaptureSession_stateCallbacks callbacks{};
        callbacks.context = this;
        callbacks.onClosed = onSessionClosed;
        callbacks.onReady = onSessionReady;
        callbacks.onActive = onSessionActive;
        const camera_status_t status = ACameraDevice_createCaptureSession(
            device_, outputContainer_, &callbacks, &captureSession_);
        if (status != ACAMERA_OK || captureSession_ == nullptr) {
            closeSessionLocked();
            return false;
        }
        return true;
    }

    bool startPreviewRepeatingLocked() {
        if (captureSession_ == nullptr || previewRequest_ == nullptr || !containerHasPreview_) return false;
        ACaptureRequest* previewRequests[] = {previewRequest_};
        int sequenceId = 0;
        return ACameraCaptureSession_setRepeatingRequest(
            captureSession_, &previewCallbacks_, 1, previewRequests, &sequenceId) == ACAMERA_OK;
    }

    void closeSessionLocked() {
        if (captureSession_ != nullptr) {
            ACameraCaptureSession_stopRepeating(captureSession_);
            ACameraCaptureSession_abortCaptures(captureSession_);
            ACameraCaptureSession_close(captureSession_);
            captureSession_ = nullptr;
        }
        if (outputContainer_ != nullptr) {
            if (containerHasPreview_ && previewOutput_ != nullptr) {
                ACaptureSessionOutputContainer_remove(outputContainer_, previewOutput_);
            }
            if (containerHasRaw_ && rawOutput_ != nullptr) {
                ACaptureSessionOutputContainer_remove(outputContainer_, rawOutput_);
            }
            ACaptureSessionOutputContainer_free(outputContainer_);
            outputContainer_ = nullptr;
        }
        containerHasPreview_ = false;
        containerHasRaw_ = false;
    }

    void configureAutomaticRequest(ACaptureRequest* request, bool still) {
        uint8_t control = ACAMERA_CONTROL_MODE_AUTO;
        uint8_t ae = ACAMERA_CONTROL_AE_MODE_ON;
        uint8_t awb = ACAMERA_CONTROL_AWB_MODE_AUTO;
        uint8_t af = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        uint8_t intent = still ? ACAMERA_CONTROL_CAPTURE_INTENT_STILL_CAPTURE : ACAMERA_CONTROL_CAPTURE_INTENT_PREVIEW;
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_MODE, 1, &control);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AE_MODE, 1, &ae);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AWB_MODE, 1, &awb);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AF_MODE, 1, &af);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_CAPTURE_INTENT, 1, &intent);
        if (still) {
            uint8_t lock = ACAMERA_CONTROL_AE_LOCK_ON;
            ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AE_LOCK, 1, &lock);
        }
    }

    void configureManualRequest(
        ACaptureRequest* request,
        int iso,
        int64_t exposureNs,
        const RawConfig& config) {
        uint8_t control = ACAMERA_CONTROL_MODE_AUTO;
        uint8_t ae = ACAMERA_CONTROL_AE_MODE_OFF;
        uint8_t awb = ACAMERA_CONTROL_AWB_MODE_AUTO;
        uint8_t af = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        uint8_t intent = ACAMERA_CONTROL_CAPTURE_INTENT_STILL_CAPTURE;
        int32_t sensitivity = std::max(config.isoMin, std::min(iso, config.isoMax));
        int64_t exposure = std::max(config.exposureMinNs, std::min(exposureNs, config.exposureMaxNs));
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_MODE, 1, &control);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AE_MODE, 1, &ae);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AWB_MODE, 1, &awb);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_AF_MODE, 1, &af);
        ACaptureRequest_setEntry_u8(request, ACAMERA_CONTROL_CAPTURE_INTENT, 1, &intent);
        ACaptureRequest_setEntry_i32(request, ACAMERA_SENSOR_SENSITIVITY, 1, &sensitivity);
        ACaptureRequest_setEntry_i64(request, ACAMERA_SENSOR_EXPOSURE_TIME, 1, &exposure);
    }

    std::string stageImage(AImage* image) {
        if (image == nullptr) return {};
        int32_t width = 0;
        int32_t height = 0;
        int64_t timestamp = 0;
        int32_t planeCount = 0;
        if (AImage_getWidth(image, &width) != AMEDIA_OK ||
            AImage_getHeight(image, &height) != AMEDIA_OK ||
            AImage_getTimestamp(image, &timestamp) != AMEDIA_OK ||
            AImage_getNumberOfPlanes(image, &planeCount) != AMEDIA_OK || planeCount < 1 ||
            width <= 0 || height <= 0) {
            return {};
        }

        uint8_t* data = nullptr;
        int dataLength = 0;
        int32_t rowStride = 0;
        int32_t pixelStride = 0;
        if (AImage_getPlaneData(image, 0, &data, &dataLength) != AMEDIA_OK || data == nullptr || dataLength <= 0 ||
            AImage_getPlaneRowStride(image, 0, &rowStride) != AMEDIA_OK || rowStride <= 0) {
            return {};
        }
        AImage_getPlanePixelStride(image, 0, &pixelStride);

        std::string cacheDir;
        RawConfig captureConfig;
        {
            std::lock_guard<std::mutex> lock(captureMutex_);
            if (!captureActive_ || captureCacheDir_.empty()) return {};
            cacheDir = captureCacheDir_;
            captureConfig = rawConfig_;
        }
        std::ostringstream fileName;
        fileName << cacheDir;
        if (!cacheDir.empty() && cacheDir.back() != '/') fileName << '/';
        fileName << "camera_ndk_raw_" << timestamp << '_' << stagedCounter_++ << ".raw16";
        const std::string path = fileName.str();

        std::ofstream output(path, std::ios::binary | std::ios::trunc);
        if (!output.good()) return {};

        bool okay = true;
        if (captureConfig.format == AIMAGE_FORMAT_RAW16) {
            const int stride = pixelStride > 0 ? pixelStride : 2;
            for (int y = 0; y < height && okay; ++y) {
                const int64_t rowStart = static_cast<int64_t>(y) * rowStride;
                if (rowStart < 0 || rowStart >= dataLength) { okay = false; break; }
                if (stride == 2 && rowStart + static_cast<int64_t>(width) * 2 <= dataLength) {
                    output.write(reinterpret_cast<const char*>(data + rowStart), static_cast<std::streamsize>(width) * 2);
                } else {
                    for (int x = 0; x < width; ++x) {
                        const int64_t offset = rowStart + static_cast<int64_t>(x) * stride;
                        uint16_t value = 0;
                        if (offset + 1 < dataLength) {
                            value = static_cast<uint16_t>(data[offset]) |
                                static_cast<uint16_t>(static_cast<uint16_t>(data[offset + 1]) << 8);
                        }
                        output.write(reinterpret_cast<const char*>(&value), sizeof(value));
                    }
                }
                okay = output.good();
            }
        } else if (captureConfig.format == AIMAGE_FORMAT_RAW12) {
            // Android RAW12 packs two 12-bit pixels into three bytes: 8 MSBs for each pixel,
            // followed by the low nibbles. Expand to little-endian uint16 samples.
            for (int y = 0; y < height && okay; ++y) {
                const int64_t rowStart = static_cast<int64_t>(y) * rowStride;
                for (int x = 0; x < width; ++x) {
                    const int group = x / 2;
                    const int within = x % 2;
                    const int64_t highOffset = rowStart + static_cast<int64_t>(group) * 3 + within;
                    const int64_t lowOffset = rowStart + static_cast<int64_t>(group) * 3 + 2;
                    uint16_t value = 0;
                    if (highOffset < dataLength && lowOffset < dataLength) {
                        const uint16_t high = data[highOffset];
                        const uint16_t packed = data[lowOffset];
                        const uint16_t low = within == 0 ? (packed & 0x0f) : ((packed >> 4) & 0x0f);
                        value = static_cast<uint16_t>((high << 4) | low);
                    }
                    output.write(reinterpret_cast<const char*>(&value), sizeof(value));
                }
                okay = output.good();
            }
        } else if (captureConfig.format == AIMAGE_FORMAT_RAW10) {
            for (int y = 0; y < height && okay; ++y) {
                const int64_t rowStart = static_cast<int64_t>(y) * rowStride;
                for (int x = 0; x < width; ++x) {
                    const int group = x / 4;
                    const int within = x % 4;
                    const int64_t highOffset = rowStart + static_cast<int64_t>(group) * 5 + within;
                    const int64_t lowOffset = rowStart + static_cast<int64_t>(group) * 5 + 4;
                    uint16_t value = 0;
                    if (highOffset < dataLength && lowOffset < dataLength) {
                        const uint16_t high = data[highOffset];
                        const uint16_t low = static_cast<uint16_t>((data[lowOffset] >> (within * 2)) & 0x3);
                        value = static_cast<uint16_t>((high << 2) | low);
                    }
                    output.write(reinterpret_cast<const char*>(&value), sizeof(value));
                }
                okay = output.good();
            }
        } else {
            okay = false;
        }
        output.close();
        if (!okay) {
            std::remove(path.c_str());
            return {};
        }

        {
            std::lock_guard<std::mutex> lock(captureMutex_);
            if (!captureActive_) {
                std::remove(path.c_str());
                return {};
            }
            capturedImages_.push_back({timestamp, width, height, path});
        }
        captureCv_.notify_all();
        return path;
    }

    void updatePreviewMeta(const ACameraMetadata* result) {
        std::lock_guard<std::mutex> lock(stateMutex_);
        lastPreviewMeta_ = readCaptureMeta(result, lastPreviewMeta_);
    }

    void addStillMeta(const ACameraMetadata* result) {
        CaptureMeta fallback;
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            fallback = lastPreviewMeta_;
        }
        const CaptureMeta parsed = readCaptureMeta(result, fallback);
        std::lock_guard<std::mutex> captureLock(captureMutex_);
        if (!captureActive_) return;
        if (parsed.timestampNs != 0) capturedMetadata_[parsed.timestampNs] = parsed;
        captureCv_.notify_all();
    }

    void markCaptureError(const std::string& error) {
        std::lock_guard<std::mutex> lock(captureMutex_);
        if (!captureActive_) return;
        if (captureError_.empty()) captureError_ = error;
        captureCv_.notify_all();
    }

    void markSequenceDone() {
        std::lock_guard<std::mutex> lock(captureMutex_);
        if (!captureActive_) return;
        captureSequenceDone_ = true;
        captureCv_.notify_all();
    }

    void resetCaptureLocked() {
        captureActive_ = false;
        captureSequenceDone_ = false;
        expectedFrames_ = 0;
        captureCacheDir_.clear();
        captureError_.clear();
        capturedImages_.clear();
        capturedMetadata_.clear();
    }

    static void freeRequests(std::vector<ACaptureRequest*>& requests) {
        for (auto* request : requests) {
            if (request != nullptr) ACaptureRequest_free(request);
        }
        requests.clear();
    }

    static void deleteStaged(const std::vector<StagedImage>& images) {
        for (const auto& image : images) {
            if (!image.path.empty()) std::remove(image.path.c_str());
        }
    }

    std::string failStartLocked(const std::string& error) {
        stopLocked();
        return error;
    }

    std::string errorJson(const std::string& message) const {
        return std::string("{\"ok\":false,\"error\":\"") + jsonEscape(message) + "\"}";
    }

    void stopLocked() {
        alive_.store(false);
        {
            std::lock_guard<std::mutex> captureLock(captureMutex_);
            if (captureActive_ && captureError_.empty()) captureError_ = "NDK camera session stopped";
        }
        captureCv_.notify_all();

        closeSessionLocked();
        strategy_ = SessionStrategy::NONE;

        if (previewRequest_ != nullptr) {
            ACaptureRequest_free(previewRequest_);
            previewRequest_ = nullptr;
        }
        if (previewTarget_ != nullptr) {
            ACameraOutputTarget_free(previewTarget_);
            previewTarget_ = nullptr;
        }
        if (rawTarget_ != nullptr) {
            ACameraOutputTarget_free(rawTarget_);
            rawTarget_ = nullptr;
        }
        if (previewOutput_ != nullptr) {
            ACaptureSessionOutput_free(previewOutput_);
            previewOutput_ = nullptr;
        }
        if (rawOutput_ != nullptr) {
            ACaptureSessionOutput_free(rawOutput_);
            rawOutput_ = nullptr;
        }
        rawWindow_ = nullptr;
        if (rawReader_ != nullptr) {
            AImageReader_delete(rawReader_);
            rawReader_ = nullptr;
        }
        if (device_ != nullptr) {
            ACameraDevice_close(device_);
            device_ = nullptr;
        }
        if (previewWindow_ != nullptr) {
            ANativeWindow_release(previewWindow_);
            previewWindow_ = nullptr;
        }
        if (characteristics_ != nullptr) {
            ACameraMetadata_free(characteristics_);
            characteristics_ = nullptr;
        }
        if (manager_ != nullptr) {
            ACameraManager_delete(manager_);
            manager_ = nullptr;
        }
        cameraId_.clear();
        rawConfig_ = {};
    }

    static void onImageAvailable(void* context, AImageReader* reader) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self == nullptr || reader == nullptr) return;
        AImage* image = nullptr;
        const media_status_t result = AImageReader_acquireNextImage(reader, &image);
        if (result != AMEDIA_OK || image == nullptr) return;
        if (self->alive_.load()) {
            const std::string path = self->stageImage(image);
            if (path.empty()) {
                std::lock_guard<std::mutex> lock(self->captureMutex_);
                if (self->captureActive_ && self->captureError_.empty()) {
                    self->captureError_ = "Unable to stage NDK RAW frame";
                    self->captureCv_.notify_all();
                }
            }
        }
        AImage_delete(image);
    }

    static void onDeviceDisconnected(void* context, ACameraDevice*) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markCaptureError("NDK camera disconnected");
    }

    static void onDeviceError(void* context, ACameraDevice*, int error) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markCaptureError("NDK camera device error " + std::to_string(error));
    }

    static void onSessionClosed(void*, ACameraCaptureSession*) {}
    static void onSessionReady(void*, ACameraCaptureSession*) {}
    static void onSessionActive(void*, ACameraCaptureSession*) {}

    static void onPreviewStarted(void*, ACameraCaptureSession*, const ACaptureRequest*, int64_t) {}
    static void onPreviewProgressed(void*, ACameraCaptureSession*, ACaptureRequest*, const ACameraMetadata*) {}
    static void onPreviewCompleted(void* context, ACameraCaptureSession*, ACaptureRequest*, const ACameraMetadata* result) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr && self->alive_.load()) self->updatePreviewMeta(result);
    }
    static void onPreviewFailed(void*, ACameraCaptureSession*, ACaptureRequest*, ACameraCaptureFailure*) {}
    static void onPreviewSequenceCompleted(void*, ACameraCaptureSession*, int, int64_t) {}
    static void onPreviewSequenceAborted(void*, ACameraCaptureSession*, int) {}
    static void onPreviewBufferLost(void*, ACameraCaptureSession*, ACaptureRequest*, ANativeWindow*, int64_t) {}

    static void onStillStarted(void*, ACameraCaptureSession*, const ACaptureRequest*, int64_t) {}
    static void onStillProgressed(void*, ACameraCaptureSession*, ACaptureRequest*, const ACameraMetadata*) {}
    static void onStillCompleted(void* context, ACameraCaptureSession*, ACaptureRequest*, const ACameraMetadata* result) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr && self->alive_.load()) self->addStillMeta(result);
    }
    static void onStillFailed(void* context, ACameraCaptureSession*, ACaptureRequest*, ACameraCaptureFailure*) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markCaptureError("NDK RAW capture request failed");
    }
    static void onStillSequenceCompleted(void* context, ACameraCaptureSession*, int, int64_t) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markSequenceDone();
    }
    static void onStillSequenceAborted(void* context, ACameraCaptureSession*, int) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markCaptureError("NDK RAW capture sequence aborted");
    }
    static void onStillBufferLost(void* context, ACameraCaptureSession*, ACaptureRequest*, ANativeWindow*, int64_t) {
        auto* self = static_cast<NativeCameraSession*>(context);
        if (self != nullptr) self->markCaptureError("NDK RAW output buffer lost");
    }

    std::mutex lifecycleMutex_;
    std::mutex stateMutex_;
    std::mutex captureMutex_;
    std::condition_variable captureCv_;
    std::atomic<bool> alive_{false};
    bool captureActive_ = false;
    bool captureSequenceDone_ = false;
    bool containerHasPreview_ = false;
    bool containerHasRaw_ = false;
    int expectedFrames_ = 0;
    uint64_t stagedCounter_ = 0;
    std::string cameraId_;
    std::string captureCacheDir_;
    std::string captureError_;
    CaptureMeta lastPreviewMeta_;
    std::vector<StagedImage> capturedImages_;
    std::map<int64_t, CaptureMeta> capturedMetadata_;
    RawConfig rawConfig_;
    SessionStrategy strategy_ = SessionStrategy::NONE;

    ACameraManager* manager_ = nullptr;
    ACameraMetadata* characteristics_ = nullptr;
    ACameraDevice* device_ = nullptr;
    ACameraCaptureSession* captureSession_ = nullptr;
    ACaptureSessionOutputContainer* outputContainer_ = nullptr;
    ACaptureSessionOutput* previewOutput_ = nullptr;
    ACaptureSessionOutput* rawOutput_ = nullptr;
    ACameraOutputTarget* previewTarget_ = nullptr;
    ACameraOutputTarget* rawTarget_ = nullptr;
    ACaptureRequest* previewRequest_ = nullptr;
    AImageReader* rawReader_ = nullptr;
    ANativeWindow* rawWindow_ = nullptr;
    ANativeWindow* previewWindow_ = nullptr;
    ACameraCaptureSession_captureCallbacks previewCallbacks_{};
    ACameraCaptureSession_captureCallbacks stillCallbacks_{};
};

std::mutex gSessionMutex;
std::shared_ptr<NativeCameraSession> gSession;

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeStartSession(
    JNIEnv* env, jobject, jstring cameraId, jobject surface) {
    if (surface == nullptr) return env->NewStringUTF("Preview surface is missing");
    const std::string id = jstringToString(env, cameraId);
    if (id.empty()) return env->NewStringUTF("Camera ID is empty");

    std::shared_ptr<NativeCameraSession> old;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        old = gSession;
        gSession.reset();
    }
    if (old) old->stop();

    auto session = std::make_shared<NativeCameraSession>();
    const std::string error = session->start(env, id, surface);
    if (!error.empty()) return env->NewStringUTF(error.c_str());
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        gSession = session;
    }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeStopSession(JNIEnv*, jobject) {
    std::shared_ptr<NativeCameraSession> session;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        session = gSession;
        gSession.reset();
    }
    if (session) session->stop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_camera_camera_camera2_NativeCameraNdkBridge_nativeCaptureBurst(
    JNIEnv* env, jobject, jstring cacheDir, jint frameCount, jfloat hdrStrength) {
    std::shared_ptr<NativeCameraSession> session;
    {
        std::lock_guard<std::mutex> lock(gSessionMutex);
        session = gSession;
    }
    if (!session) return env->NewStringUTF("{\"ok\":false,\"error\":\"NDK camera session is not active\"}");
    const std::string result = session->captureBurst(
        jstringToString(env, cacheDir), static_cast<int>(frameCount), static_cast<float>(hdrStrength));
    return env->NewStringUTF(result.c_str());
}
