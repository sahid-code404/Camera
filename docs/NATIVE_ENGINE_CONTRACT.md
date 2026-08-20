# Native Engine Contract

## Purpose

This document defines which parts of Camera are allowed to depend on Android framework APIs and which parts must belong to our native engine.

The goal is maximum control, performance and reproducibility without pretending a normal non-root application can bypass the vendor camera HAL/kernel.

## 1. Boundary rule

Android is the hardware-access boundary.

Our engine owns the photographic/media decisions after the device has delivered a buffer and metadata.

Allowed Android responsibilities:
- permissions,
- app lifecycle,
- display surfaces,
- camera/HAL access,
- sensor stream negotiation,
- device capability discovery,
- physical/logical camera routing,
- MediaStore/public file publication,
- hardware codec access when the user selects the hardware-encode path.

Android must not be treated as the owner of:
- RAW sample modification,
- our exposure planning logic,
- our metadata/calibration model,
- our video color pipeline,
- our stabilization algorithm,
- our scaling policy,
- our final software-encoded bitstream,
- our muxing policy,
- our validation logic.

## 2. Language split

### Kotlin / Compose
Use only for:
- UI,
- settings,
- lifecycle orchestration,
- permissions,
- lightweight state machines,
- user interaction,
- diagnostics presentation.

Do not put full-frame image processing loops in Kotlin.

### Native C++
C++ is the primary high-performance core because Android NDK camera, image, hardware-buffer, codec and Vulkan interfaces are C/C++ friendly.

Use modern C++ for:
- native buffer ownership,
- RAW packing/checking,
- metadata normalization,
- CFA utilities,
- matrix/color-calibration math,
- histogram/statistics kernels,
- motion estimation,
- video frame graph,
- stabilization,
- pixel-format conversion,
- scaling,
- software encoder integration,
- muxing/container writing,
- DNG/TIFF parsing and validation,
- native test corpus tools.

### SIMD / GPU
Use:
- ARM NEON for CPU hot loops,
- Vulkan compute for kernels that benchmark faster after transfer/synchronization cost,
- AHardwareBuffer where it reduces copies and is supported reliably.

Never move a kernel to Vulkan just because a GPU exists.

## 3. Memory rules

Prefer:
- bounded queues,
- RAII ownership,
- direct/native buffers,
- memory mapping for large temporary data when appropriate,
- AHardwareBuffer/zero-copy paths where available,
- one conversion at a pipeline boundary rather than repeated conversions.

Avoid:
- Bitmap as a processing master,
- repeated JNI copies,
- ByteArray copies of full-resolution frames,
- unbounded capture queues,
- retaining Image/AImage objects after their data/metadata has been safely consumed.

## 4. Still-photo contract

Production PHOTO is strict native RAW.

Normal capture:

```text
sensor
  -> RAW_SENSOR
  -> matching capture metadata
  -> untouched CFA samples
  -> DNG
  -> one persisted photo
```

No production still-photo operation may apply to the saved CFA samples:
- denoise,
- sharpening,
- saturation,
- tone mapping,
- HDR merge,
- frame averaging,
- super-resolution,
- interpolation,
- local contrast,
- skin processing,
- RGB conversion,
- gamma.

Preview analysis may influence the exposure/focus/WB metadata decision, but it does not modify the stored RAW samples.

## 5. RAW integrity

The native core must model, inspect and test:
- RAW dimensions,
- row stride,
- pixel stride,
- bit packing,
- CFA arrangement,
- black level,
- dynamic black level,
- white level,
- active/pre-correction arrays,
- optical black regions,
- neutral color point,
- noise profile,
- lens shading metadata,
- color/forward/calibration matrices,
- reference illuminants,
- exposure/ISO,
- focal/aperture/focus metadata,
- orientation,
- camera/physical-camera identity.

Never hard-code RGGB or normalize every sensor to 16-bit full scale.

## 6. DNG writer policy

The final DNG must be standards-compliant and independently verifiable.

Short-term:
- Android DngCreator may be used only as a standards-correct bridge while the native metadata/validation layer is built.

Long-term:
- if DngCreator prevents required control, use a native DNG/TIFF implementation,
- prefer a proven standards implementation/SDK or a carefully tested native writer,
- do not ship a half-correct serializer simply to claim zero Android dependencies.

The native validation layer must inspect actual generated files, including:
- TIFF/DNG Compression tag,
- BitsPerSample,
- CFA tags,
- dimensions,
- black/white level tags,
- matrices,
- orientation,
- strip/tile offsets,
- sample integrity.

## 7. Exposure intelligence

Our native analyzer may consume preview/YUV statistics and sensor metadata to implement:
- histogram,
- highlight clipping estimate,
- shadow distribution,
- motion estimate,
- face brightness estimate,
- focal-length/OIS-aware shutter limits,
- ETTR-like highlight-safe exposure planning,
- focus confidence,
- AWB assistance.

Output of this subsystem is a capture plan, not rendered pixels.

## 8. Video contract

Video is not the same as strict RAW still photography.

The pipeline must distinguish three layers:

### Layer A — acquisition
Use the best public stream the device actually exposes:
- YUV_420_888,
- P010/10-bit path where truly available,
- PRIVATE surface where a zero-copy hardware path is required,
- RAW only if the HAL can actually sustain the requested video rate.

We cannot promise RAW video on devices whose HAL does not expose it.

### Layer B — our native frame graph
When CPU/GPU-accessible frames are available, our engine owns:
- crop,
- orientation,
- color transform,
- tone mapping,
- denoise,
- sharpening/detail,
- stabilization,
- HDR mapping,
- scaling,
- frame interpolation when explicitly enabled/labeled,
- overlays/metadata policy.

Every stage must be configurable per lens and have an explicit on/off/bypass state.

### Layer C — encoding and container
Provide two clearly different paths:

#### Full-control native/software path
Native encoder library/implementation owns:
- GOP,
- keyframes,
- bitrate/rate control,
- quantization policy,
- profile/level constraints,
- B-frame/reference policy where codec permits,
- timestamps,
- bitstream packaging.

Our muxer/container writer owns final file assembly.

This path maximizes control but can cost more CPU, power and heat.

#### Hardware-efficient path
Use Android NDK MediaCodec only when the user/device needs hardware efficiency.

Even in this mode, our app still owns:
- requested resolution/FPS,
- preprocessing when accessible,
- bitrate policy,
- fallback policy,
- metadata labeling,
- container finalization.

The hardware codec is an accelerator, not the definition of our image aesthetic.

## 9. Video truthfulness

Never claim:
- native 4K if source frames were lower resolution,
- native 10-bit if source was 8-bit,
- native HDR if the source/metadata was not HDR,
- native 120/240 fps if generated by interpolation,
- RAW video if the frames came from YUV/PRIVATE ISP output.

## 10. From-scratch meaning

For this project, "from scratch" means:
- our architecture,
- our state machines,
- our exposure logic,
- our calibration model,
- our processing graph,
- our native kernels,
- our quality/fallback policy,
- our validation,
- our file/container policy.

It does not mean reimplementing a proprietary vendor kernel driver or bypassing Android security.

It also does not require writing a standards-incompatible DNG writer or a brand-new H.265 encoder merely to avoid a mature low-level library.

## 11. Performance measurements

Every native optimization must be measured on real hardware.

Record:
- capture latency,
- preview frame time,
- memory copies/frame,
- peak RAM,
- CPU utilization,
- GPU utilization,
- thermal state,
- encoder throughput,
- dropped frames,
- DNG write throughput,
- file finalization latency.

Optimize the measured bottleneck, not an assumed one.

## 12. Non-negotiable architecture test

A code review should be able to answer:

1. Where did this frame originate?
2. Which Android/HAL stage touched it before our engine?
3. Which of our native stages touched it?
4. Was any conversion lossy?
5. Who chose its exposure/color/scale/encode policy?
6. Does the UI label its true capability accurately?
7. Can the output be independently validated?

If those answers are ambiguous, the feature is not ready.
