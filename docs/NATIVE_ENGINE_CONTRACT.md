# Native Engine Contract

## Purpose

This document defines the permanent architecture boundary for `Camera`.

The goal is maximum control, performance and reproducibility while acknowledging that a normal non-root Android application must still use Android/vendor interfaces to access camera hardware.

The final photographic decisions belong to **our engine**.

---

## 1. Boundary rule

Android / the vendor HAL is the hardware-access boundary.

Allowed Android responsibilities:
- permissions,
- lifecycle,
- surfaces,
- public camera access,
- sensor stream negotiation,
- physical/logical camera routing,
- capability discovery,
- storage publication,
- hardware codec access for optional efficient video paths.

Our engine owns:
- capture planning,
- RAW burst policy,
- frame selection,
- alignment,
- HDR fusion,
- denoise,
- highlight/shadow processing,
- saturation/vibrance,
- sharpening/texture,
- color processing,
- scaling/upscale/MFSR policy,
- video frame processing,
- stabilization,
- software encoding policy,
- muxing/container policy,
- output validation.

Android must not define the aesthetic merely because it delivered the source buffer.

---

## 2. Language split

### Kotlin / Compose

Use for:
- UI,
- settings,
- permissions,
- lifecycle,
- lightweight orchestration/state machines,
- diagnostics presentation,
- passing compact configuration/metadata across the JNI boundary.

Do not implement full-frame image-processing loops in Kotlin.

### Native C++

C++ is the primary high-performance engine.

Use for:
- RAW buffer processing,
- CFA utilities,
- frame alignment,
- motion/confidence maps,
- HDR fusion,
- noise-aware fusion,
- detail/sharpening,
- saturation/color math,
- crop/scale/upscale,
- MFSR,
- histogram/statistics kernels,
- video frame graph,
- stabilization,
- format conversion,
- software encoder integration,
- muxing/container code,
- DNG/TIFF parsing/validation and eventually writing where required.

### SIMD / GPU

Use:
- ARM NEON for measured CPU hot loops,
- Vulkan compute for kernels that benchmark faster after synchronization/transfer overhead,
- `AHardwareBuffer` where it genuinely removes copies.

Do not move an operation to Vulkan only because a GPU exists.

---

## 3. Memory/performance rules

Prefer:
- bounded queues,
- RAII native ownership,
- memory mapping for large RAW scratch frames,
- direct/native buffers,
- zero/minimal-copy paths,
- fixed worker pools,
- one conversion per required pipeline boundary.

Avoid:
- Bitmap as a photo master,
- Java/Kotlin full-resolution pixel arrays for processing,
- repeated JNI copies of full frames,
- unbounded captures,
- retaining Camera2 `Image` objects longer than necessary.

---

## 4. PHOTO output contract

PHOTO persists **one DNG only** per normal shutter press.

No persisted photographic:
- JPEG,
- HEIC/HEIF,
- PNG,
- WebP.

The DNG is a **computational DNG** and may contain Bayer/CFA samples created by our processing pipeline rather than untouched samples from one exposure.

This is intentional.

Do not falsely describe computational output as untouched sensor RAW.

Target:

```text
RAW_SENSOR burst
 -> exact Image/CaptureResult pairing
 -> native C++ processing
 -> one computational Bayer master
 -> DNG container
 -> one saved photo
```

If a lens exposes no trustworthy `RAW_SENSOR` route, PHOTO must not fake it with JPEG/YUV-to-DNG conversion. That lens may remain available for VIDEO or other future modes where appropriate.

---

## 5. Computational DNG stages

The production native graph may include, depending on per-lens settings and mode:
- reference-frame scoring,
- exposure normalization,
- HDR/bracket fusion,
- constant-exposure temporal fusion,
- motion rejection,
- denoise,
- highlight shaping,
- shadow shaping,
- bad/hot-pixel handling,
- saturation,
- vibrance,
- warmth/tint,
- sensor/lens-specific color processing,
- same-CFA detail/sharpening,
- aspect crop,
- interpolation/upscale,
- genuine multi-frame super-resolution,
- Night/long-exposure stacking strategies.

All stages need explicit configuration and must be independently bypassable where appropriate.

Per-lens settings are authoritative.

---

## 6. Upscale vs true MFSR

These are different features.

### Upscale
- may interpolate Bayer/CFA planes,
- may produce larger DNG dimensions,
- is a user-selectable processing option,
- must remain identifiable internally as upscale.

### True MFSR
- requires multiple real sub-pixel observations,
- requires local/sub-pixel alignment and confidence checks,
- must add information derived from actual frames rather than only interpolation.

Never call simple interpolation genuine MFSR.

---

## 7. Current native PHOTO implementation

The first native engine uses:
- C++20,
- NDK/CMake,
- `mmap` input frames,
- reference selection,
- CFA-safe even-pixel alignment,
- motion rejection,
- exposure-normalized multi-frame fusion,
- HDR bracket use,
- highlight/shadow shaping,
- sensor-space saturation,
- same-CFA sharpening,
- Bayer-preserving upscale,
- CFA-safe aspect crop.

This is the starting point, not the final flagship algorithm.

Next upgrades:
- gyro initialization,
- image pyramid,
- local/tile alignment,
- sub-pixel refinement,
- confidence map,
- rolling-shutter correction,
- noise-profile weighting,
- better reference scoring,
- NEON acceleration,
- selectively Vulkan kernels,
- genuine MFSR.

---

## 8. DNG writer policy

The final saved photograph must be standards-compatible DNG.

Short-term bridge:
- Android `DngCreator` may package our processed Bayer master while we validate metadata and file structure.

`DngCreator` in this role is a container/metadata bridge; it does not own HDR, saturation, highlights, denoise, sharpness or upscale decisions.

Required validation:
- output dimensions,
- CFA tags,
- BitsPerSample,
- Compression tag,
- black/white levels,
- color matrices,
- crop/active-array metadata,
- orientation,
- strip/tile offsets,
- Lightroom/ACR compatibility,
- darktable/RawTherapee compatibility.

If framework DNG writing cannot represent transformed/cropped/upscaled computational Bayer correctly, replace it with a tested native standards-compliant DNG/TIFF writer.

Do not build a half-correct custom DNG just to remove a framework dependency.

---

## 9. Per-lens PHOTO configuration

Every valuable RAW-capable lens may independently configure:
- HDR on/off/auto policy,
- HDR strength,
- highlight behavior,
- shadow behavior,
- denoise,
- temporal denoise,
- sharpness,
- texture,
- saturation,
- vibrance,
- warmth,
- tint,
- color profile,
- lens corrections,
- upscale factor,
- genuine MFSR,
- capture-frame count/quality tier,
- focus/exposure behavior,
- Night-mode policy.

A setting is not considered implemented merely because a field exists. It becomes implemented only when a native stage consumes it and device tests verify its effect.

---

## 10. Global aspect rule

Photo aspect is camera-wide, not per lens.

Default: `4:3`.

Other initial choices:
- `1:1`,
- `16:9`.

Changing lenses/facing must not change the chosen composition.

The preview and final computational DNG composition must agree.

CFA crop origins/dimensions must preserve valid mosaic parity.

---

## 11. Video contract

Video is independent of the DNG-only PHOTO output rule.

### Acquisition

Use the best real stream publicly exposed by the selected lens/device:
- YUV,
- P010/10-bit where real,
- PRIVATE/zero-copy path where useful,
- RAW only when the HAL can sustain a genuine RAW video stream.

### Native frame graph

When frames are CPU/GPU accessible, our engine owns:
- crop/orientation,
- color/WB,
- HDR/tone,
- denoise,
- sharpening/detail,
- stabilization,
- scaling/upscale,
- frame interpolation when explicitly requested,
- timestamp/frame pacing policy.

### Encoding

Provide two paths eventually:

1. **Full-control software/native path**
   - our codec configuration,
   - GOP/keyframes,
   - quantization/rate control,
   - references/B-frames where relevant,
   - timestamps,
   - bitstream packaging,
   - our muxing/container layer.

2. **Hardware-efficient path**
   - Android NDK codec used as an accelerator when efficiency/heat requires it,
   - our preprocessing and fallback policy remain authoritative where frames are accessible.

---

## 12. Per-lens VIDEO settings

Every lens should eventually expose independently:
- resolution,
- FPS,
- codec,
- bitrate,
- bit depth,
- HDR/dynamic range,
- stabilization,
- OIS/EIS preference,
- shutter/ISO,
- focus,
- WB,
- audio profile,
- lens switching,
- thermal fallback,
- experimental combinations,
- upscaled-output policy.

---

## 13. Forced 4K policy

Users may request 4K output even when the source lens cannot provide native 4K.

Policy:
1. Use native 3840x2160 when the camera and encoder path actually provide it.
2. Try guarded experimental native combinations when metadata is ambiguous and session creation may succeed.
3. If native 4K fails, obtain the best real source stream.
4. When user explicitly wants 4K output, our native scaler may produce 3840x2160 before encoding.
5. Keep reliable fallback chains for resolution/FPS/codec/thermal limits.
6. Track whether the source was native or upscaled internally; never corrupt capability diagnostics.

Same principle applies to FPS and bit depth.

---

## 14. Lens discovery contract

The main UI should show photographic lenses, not raw Camera2 IDs.

Use:
- logical/physical graph,
- session validation,
- optical fingerprints,
- focal length/FOV/sensor data,
- route preference,
- user confirmation.

Hide by default:
- depth-only routes,
- non-openable routes,
- logical aggregators that duplicate physical glass,
- vendor aliases that represent the same optics.

Let the user:
- show/hide lenses,
- reorder lenses,
- confirm role,
- rename lens,
- set custom zoom label.

---

## 15. From-scratch meaning

For `Camera`, from scratch means our:
- architecture,
- camera state machines,
- lens model,
- capture policy,
- native processing graph,
- algorithms/kernels,
- quality/fallback policy,
- per-lens configuration,
- video processing,
- validation,
- file/container policy,
- UI interaction design.

It does not require reimplementing the vendor's kernel camera driver or bypassing Android security.

---

## 16. Performance measurements

Measure on real devices:
- shutter/acquisition latency,
- RAW staging throughput,
- native processing time per stage,
- peak RAM,
- disk scratch throughput,
- CPU use,
- GPU use,
- thermal state,
- preview frame time,
- video dropped frames,
- encoder throughput,
- file-finalization latency.

Optimize measured bottlenecks.

---

## 17. Architecture review checklist

For every output, reviewers must be able to answer:
1. Which logical/physical lens supplied the source?
2. Which format/resolution did the HAL actually deliver?
3. Which native stages touched the data?
4. Which per-lens settings were active?
5. Was any operation destructive/lossy by design?
6. Is the output native-resolution, interpolated upscale or genuine MFSR?
7. Does the preview composition match the output composition?
8. Can the final DNG/video be independently validated?

If those answers are ambiguous, the feature is not ready.
