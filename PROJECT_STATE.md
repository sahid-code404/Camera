# Project State

## Current Direction

The strict RAW specification is authoritative for still photography.

Normal PHOTO capture must be:

`one shutter press -> one genuine RAW_SENSOR exposure -> one native DNG`

No rendered photographic companion is persisted.

The selected photo composition aspect is camera-wide, defaults to `4:3`, and remains selected across every lens/facing.

## Important correction

A recent experimental branch step introduced four-frame RAW fusion and a computational DNG path. That path conflicts with the authoritative strict-RAW specification and must not be treated as the production photo architecture.

Production still-photo rules:
- no RAW averaging,
- no HDR RAW merge,
- no multi-frame denoise,
- no frame stacking,
- no RAW super-resolution,
- no sharpening/texture/tone mapping of Bayer values,
- no JPEG/HEIC/PNG photographic companion,
- no fake RAW on lenses that do not expose RAW_SENSOR,
- one native RAW exposure per normal shutter press.

The experimental fusion code must be removed from the production shutter path or isolated for a future separate rendered/computational mode that does not masquerade as untouched RAW.

## Native performance requirement

Kotlin/Compose is the UI and orchestration layer only.

Performance-critical camera and media work is moving toward a native engine, primarily C++ with:
- Android NDK camera interfaces where practical,
- AImageReader/AHardwareBuffer-oriented buffer handling where practical,
- zero/minimal-copy pipelines,
- bounded native worker queues,
- ARM NEON SIMD,
- Vulkan compute where profiling proves a win,
- native metadata/calibration math,
- native DNG validation/writing strategy,
- native video frame processing,
- native container/bitstream control where feasible.

Android APIs/HAL remain unavoidable for legal non-root access to camera hardware, permissions, surfaces, storage integration, and some device hardware codecs. They are transport/control boundaries, not the owner of our photographic rendering decisions.

## Still-photo target

`Sensor -> RAW_SENSOR -> exact Image/CaptureResult pairing -> untouched CFA samples + complete metadata -> standards-compliant DNG -> one saved photo`

Capture intelligence may analyze preview/YUV data for exposure, focus, motion, highlight protection, AWB assistance, histogram, zebras and focus peaking. None of that may alter the saved Bayer samples.

DNG metadata must preserve/validate, per physical lens where available:
- native RAW dimensions,
- CFA arrangement,
- black level and dynamic black level,
- white level,
- exposure/ISO,
- focal length/aperture/focus distance,
- neutral color point,
- ColorMatrix1/2,
- ForwardMatrix1/2,
- CalibrationTransform1/2,
- ReferenceIlluminant1/2,
- noise profile,
- lens shading information,
- crop/active-array metadata,
- orientation,
- physical camera identity.

## DNG ownership

Start from the most standards-correct implementation and verify the resulting TIFF/DNG structure. Long-term, final-file ownership should not depend on Android rendering decisions.

If Android DngCreator limits required metadata/control, move to a native standards-compliant DNG writer. Do not ship a half-correct custom TIFF/DNG implementation merely to remove an API dependency. Native sample integrity and compatibility with Lightroom/ACR/darktable/RawTherapee are higher priority than ideological API removal.

## Video direction

Video is separate from the strict RAW-still rule.

Target architecture:
- Android/NDK camera layer obtains the best real sensor/ISP stream the device publicly exposes,
- our native pipeline owns frame processing, color/tone decisions, stabilization logic, scaling policy, timestamps and fallback decisions,
- software/native codec path provides maximum bitstream/rate-control ownership where practical,
- hardware codec path may exist as an explicit performance mode when the user prefers efficiency,
- our own muxing/container layer is preferred where practical,
- never claim RAW video, native 10-bit, native 4K, native HDR or native high FPS when the HAL did not actually deliver it.

A normal non-root Android application cannot bypass the vendor camera HAL/kernel. Full RAW video is only possible when the hardware/HAL exposes a RAW stream at the required frame rate and bandwidth.

## Already implemented / useful foundation

- App name `Camera`.
- Gradle/Android CI.
- Public Camera2 capability discovery.
- Logical/physical route graph.
- Valuable-lens filtering.
- route open/session probing.
- Lens Manager and persistent lens ordering/labels.
- real Camera2 preview.
- rear/front lens switching.
- camera ownership recovery.
- sharp square preview handling.
- global photo aspect with `4:3` default.
- tap-to-focus / AF/AE metering.
- pinch zoom.
- RAW capability/resolution metadata foundation.

## Immediate correction work

1. Remove the experimental multi-frame fusion path from the production shutter.
2. Restore strict single-exposure RAW_SENSOR -> DNG capture.
3. Ensure RAW-only lens filtering in RAW/PHOTO mode.
4. Add robust image/result pairing and timeout cleanup.
5. Add native RAW metadata/calibration structures.
6. Add DNG inspection/validation tooling and automated integrity tests.
7. Add the native C++ core and move hot buffer/metadata/video work out of Kotlin.
8. Real-device validate every RAW-capable physical lens.
9. Only after still-RAW integrity is proven, build the native video pipeline.

## Non-negotiable quality rules

- Preserve photons and sensor measurements before aesthetics.
- Never fake unsupported RAW, resolution, FPS, HDR, bit depth or lens capability.
- Never alter Bayer samples merely to make a DNG look prettier in a gallery.
- Never call processed RGB sensor RAW.
- Never use interpolation as claimed sensor resolution.
- Never mark a mode complete until code, CI and real-device behavior are validated.
