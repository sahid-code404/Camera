# Project State

## Authoritative Product Direction

`Camera` is a from-scratch, highly optimized Android camera application with an iOS-26-inspired interaction model, deep per-lens control, useful auxiliary-camera access, and a native processing core.

For PHOTO, the persisted photographic output is **DNG only**.

This is intentionally a **computational DNG** pipeline, not an untouched/scientific RAW-only pipeline.

Production PHOTO target:

```text
Camera HAL / RAW_SENSOR
        -> short RAW burst
        -> exact Image/CaptureResult pairing
        -> our native C++ processing
        -> one processed Bayer master
        -> one DNG
```

No `.jpg`, `.jpeg`, `.heic`, `.heif`, `.png` or WebP photograph is persisted by PHOTO.

Android is the unavoidable hardware-access boundary. It is not the owner of our photographic processing decisions.

## Current Branch

Active development: `phase/01-foundation-discovery`

Draft PR: `#1`

## Implemented and CI-validated foundation

- App name `Camera`.
- Compose camera shell.
- Official Gradle wrapper and GitHub Actions CI.
- SnapCam-compatible development package identity for vendor auxiliary-camera exposure on compatible ROMs.
- Public Camera2 metadata discovery.
- Logical/physical camera route graph.
- Valuable-lens filtering without hard-coded numeric camera IDs.
- Duplicate/vendor-alias filtering using optical fingerprints.
- Camera route open/session validation.
- Lens Manager:
  - visibility,
  - order,
  - confirmed role,
  - custom name,
  - custom zoom label.
- Persistent stable per-lens identity/configuration.
- Real Camera2 live preview.
- Rear/front lens switching.
- Camera ownership recovery when another camera application temporarily takes the camera.
- Sharp `1:1` preview using a high-quality native stream + crop rather than stretching tiny square streams.
- Camera-wide Photo aspect with default `4:3`; `1:1` and `16:9` persist across lenses/facings.
- Unmirrored front preview.
- Tap-to-focus and AF/AE metering.
- Pinch zoom.
- OTA development-update foundation.

## Native Computational DNG Engine — current state

The production PHOTO shutter no longer has a persisted JPEG fallback.

PHOTO exposes only enabled valuable lenses that actually expose `RAW_SENSOR`.

Current capture path:

```text
RAW_SENSOR x4
 -> timestamp/result pairing
 -> file-backed RAW16 staging
 -> C++ mmap input
 -> reference selection
 -> CFA-safe global alignment
 -> motion rejection
 -> exposure normalization
 -> optional HDR bracket fusion
 -> denoise through robust multi-frame fusion
 -> highlight/shadow shaping
 -> sensor-space saturation
 -> same-CFA sharpening
 -> optional Bayer-preserving 1x-4x upscale
 -> CFA-safe global aspect crop
 -> one computational Bayer master
 -> DNG
```

The expensive full-frame loops are in native C++ (`:processing-raw`) rather than Kotlin.

Current native build:
- C++20,
- Android NDK 27.2,
- CMake 3.22.1,
- `-O3`,
- `arm64-v8a`,
- `armeabi-v7a`.

Kotlin is currently responsible for Camera2 orchestration, frame/result pairing, settings transport and DNG publication; it does not own the full-frame pixel kernels.

## Per-lens computational PHOTO controls currently wired

Persisted independently for every stable lens:
- HDR on/off,
- HDR strength,
- highlight protection,
- shadow recovery,
- denoise,
- saturation,
- sharpness,
- DNG upscale factor (1x-4x).

The data model already also contains fields for:
- temporal denoise,
- texture,
- vibrance,
- warmth,
- tint,
- true multi-frame super-resolution.

Those additional controls must not be presented as effective until their native kernels are actually implemented.

## Important quality boundary

The current native engine is a **working first computational engine**, not the final flagship pipeline.

Current alignment is global, integer, even-pixel/CFA-safe translation. It is deliberately conservative.

Next image-quality work must add:
1. gyro-assisted initialization,
2. image-pyramid alignment,
3. local/tile motion fields,
4. sub-pixel refinement,
5. confidence maps,
6. rolling-shutter-aware correction where useful,
7. noise-profile-aware weighting,
8. better reference-frame scoring,
9. hot/bad-pixel handling,
10. stronger edge-aware detail processing,
11. true MFSR only when real sub-pixel observations provide extra detail,
12. NEON acceleration after profiling,
13. Vulkan compute only for kernels that benchmark faster end-to-end.

`Upscale 2x/3x/4x` is currently an explicit Bayer-preserving interpolation option. It is **not** called true MFSR.

## DNG ownership / current limitation

The final saved photograph is DNG only.

At this stage Android `DngCreator` is still used as a container/metadata bridge around our processed Bayer master. It is **not** used to render the image or decide HDR/saturation/highlight/sharpness/upscale.

This bridge must be validated on real device captures, especially after crop/upscale, for:
- output dimensions,
- CFA tags,
- black/white levels,
- color matrices,
- active/crop metadata,
- strip/tile layout,
- compression tag,
- compatibility with Lightroom/ACR/darktable/RawTherapee.

If `DngCreator` cannot represent our transformed computational Bayer reliably, replace the writer with a tested native standards-compliant DNG/TIFF implementation. Do not ship a broken custom DNG merely to remove an Android dependency.

## PHOTO rules

- Persist DNG only.
- Do not persist JPEG/HEIC/PNG photo companions.
- Do not fake RAW on a route without `RAW_SENSOR`.
- Do not hard-code camera IDs.
- Per-lens settings must actually affect the selected lens only.
- Global aspect stays consistent across lenses.
- User lens visibility/order/labels remain authoritative.
- A processed computational DNG must not be described as untouched sensor RAW.
- Upscale must not be described as true MFSR.

## VIDEO target

Video is a separate native pipeline.

Target:

```text
best real camera stream exposed by HAL
 -> native C++ / Vulkan frame graph
 -> our color / denoise / HDR / stabilization / scaling
 -> encoder
 -> our container/muxing policy
 -> final video
```

Per-lens video configuration must include:
- resolution,
- FPS,
- codec,
- bitrate,
- 8/10-bit,
- HDR,
- stabilization,
- focus,
- WB,
- shutter/ISO,
- audio,
- lens switching,
- thermal fallback,
- experimental combinations,
- optional upscaled output.

4K policy:
- use true native 4K when the camera + encoder path supports it,
- guarded experimental attempts where metadata is ambiguous,
- when native 4K is impossible and user requests 4K output, process the best real source into 3840x2160 and treat it internally as upscaled 4K,
- keep fallback chains so recording remains reliable.

Never fabricate native resolution/FPS/bit depth/HDR capability.

## Product modes still planned

- PHOTO
- VIDEO
- PORTRAIT
- NIGHT
- PRO
- SLO-MO
- PANO
- TIME-LAPSE
- LONG EXPOSURE
- ASTRO
- Burst / Best Shot

Do not make a mode look functional before its actual engine exists.

## Immediate next work

1. Real-device test the current native computational DNG path at **1x upscale first**.
2. Validate DNG metadata/dimensions in independent RAW tools.
3. Validate HDR on/off and per-lens settings visibly change only the intended lens.
4. Benchmark native processing time, RAM and thermal behavior on the test device.
5. Add local/sub-pixel alignment + confidence maps.
6. Add NEON kernels for measured CPU bottlenecks.
7. Implement genuine MFSR separately from simple upscale.
8. Build more per-lens controls (texture/vibrance/warmth/tint/color profiles).
9. Build the native video engine and per-lens video settings.
10. Continue iOS-26-style interaction/zoom/lens-transition polish.

## CI milestone

Native integration build reached:
- unit tests: PASS,
- lint: PASS,
- APK assembly including native C++ libraries: PASS.

The build still requires physical-device DNG validation before this photo engine is called production-ready.

## Non-negotiable engineering rule

For every output, we must be able to state:
1. which physical/logical camera delivered the source,
2. what real resolution/format it delivered,
3. which native stages modified it,
4. which per-lens settings were active,
5. whether output resolution is native, upscaled, or genuine MFSR,
6. whether the final file is structurally valid and independently readable.
