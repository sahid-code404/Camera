# Camera — Master Build Specification

## 1. Mission

Build a from-scratch Android application named **Camera** that prioritizes:

1. camera reliability,
2. no lost photos/videos,
3. maximum useful hardware access through public Android APIs,
4. fast preview and shutter response,
5. excellent photo quality,
6. excellent video quality,
7. low-end-to-flagship scalability,
8. deep per-lens configurability,
9. smooth iOS-26-inspired interaction,
10. honest capability/fallback behavior.

Use original UI assets and icons. The interaction may be very close to iOS 26, but do not ship Apple proprietary artwork, source code, or branding.

---

## 2. Platform direction

- Kotlin
- Jetpack Compose
- Camera2 as the low-level authority
- CameraX only where it improves reliability, lifecycle integration, OEM Extensions, or fallback
- Coroutines + Flow
- DataStore for preferences
- Room only if structured capture/index data becomes necessary
- MediaStore for public media
- Android NDK/C++ for proven hot processing paths
- ARM NEON where profiling justifies it
- Vulkan compute where it provides real wins
- LiteRT for small on-device models, using GPU/NPU delegates when available
- MediaCodec + MediaMuxer for the primary advanced video pipeline
- minSdk 28 initially
- target/compile current stable Android SDK

---

## 3. Product modes

Final intended mode carousel:

- SLO-MO
- VIDEO
- PHOTO
- PORTRAIT
- NIGHT
- PRO
- PANO
- TIME-LAPSE

Additional advanced modes can live in More:
- Burst / Best Shot
- Long Exposure
- Astro
- Motion Photo
- Document
- Macro (only when useful hardware/focus path exists)

Do not display a mode as functional before it is implemented.

---

## 4. iOS-26-inspired camera UX

Visual principles:
- edge-to-edge viewfinder,
- dark chrome around the capture area only when required by aspect ratio,
- translucent/liquid-glass controls,
- large central shutter,
- animated lens/zoom pills,
- mode strip directly below/around the shutter area,
- minimal main-screen text,
- top controls in compact glass circles/pills,
- fluid spring animations,
- haptic feedback for lens stops, mode selection, exposure reset, and capture,
- one-hand reachability.

Exact Apple assets/fonts/icons must not be copied. Use original vector assets and Android system typography tuned to visually similar spacing.

### Core interaction
- horizontal swipe switches capture modes,
- pinch zooms continuously,
- tapping a lens pill selects the user-confirmed lens position,
- long-press lens pill opens focal/zoom scrubber,
- tap viewfinder focuses/meters,
- vertical drag beside focus point adjusts exposure compensation,
- double tap may flip camera if enabled,
- shutter tap captures,
- shutter long press may QuickTake video if enabled,
- swipe shutter toward lock keeps QuickTake recording,
- gallery thumbnail opens internal gallery,
- top/right menu opens per-lens quick settings.

### Zoom/lens animation
The displayed zoom value is not hardcoded globally.
It is derived from:
- user-confirmed lens ordering/positions,
- focal length,
- sensor size/FOV,
- logical-camera zoom ranges,
- selected base-equivalent lens.

Animation:
- spring-based pill expansion,
- crossfade of focal label,
- preview crossfade only when a hard camera-device switch is unavoidable,
- avoid black frames by prewarming a second camera only when concurrent-camera support allows it,
- prefer logical camera seamless zoom when the HAL does it well,
- use physical-camera selection only when it is demonstrably better.

---

## 5. Camera discovery and valuable-lens model

Enumerate every public Camera2 ID.

For every ID collect:
- lens facing,
- hardware level,
- capabilities,
- physical IDs,
- logical-camera membership,
- sensor physical size,
- active/pixel array,
- focal lengths,
- aperture,
- minimum focus distance,
- OIS/EIS,
- flash,
- zoom range,
- distortion correction,
- optical stabilization,
- RAW support,
- YUV/JPEG/HEIC/Ultra HDR,
- dynamic range profiles,
- color space profiles,
- manual sensor controls,
- manual post-processing,
- burst capability,
- reprocessing,
- high-speed video configurations,
- supported output combinations,
- stream minimum frame durations,
- stall durations,
- exposure/ISO ranges,
- AF/AE/AWB modes,
- noise profile,
- black levels,
- white level,
- lens shading map capability,
- timestamp source,
- rolling-shutter skew where available.

### ValuableCameraResolver

Raw Camera2 IDs must be converted into a user-facing list of useful photographic lenses.

Reject/hide by default:
- depth-only routes,
- logical aggregator duplicates,
- vendor aliases with identical optics/stream behavior,
- non-openable routes,
- routes unable to create a photographic session.

Allow advanced users to reveal hidden/diagnostic routes.

Lens role classification:
- ultra-wide,
- wide/main,
- tele,
- long tele/periscope,
- macro,
- monochrome,
- front wide,
- front ultra-wide,
- depth/auxiliary (diagnostics unless photographically useful).

Use confidence, not certainty, when metadata is ambiguous.

### User-confirmed lens position

After discovery, provide a Lens Manager:
- show preview from each useful lens,
- show optical metadata,
- user may rename the lens,
- user may confirm its role,
- user may enable/disable it,
- user may reorder it,
- user may assign displayed zoom label (e.g. 0.6×, 1×, 2×),
- at least one camera per desired facing remains enabled.

Store configuration against stable lens identity.

---

## 6. Vendor/auxiliary compatibility

Some ROMs filter auxiliary cameras based on package identity or vendor allowlists.

Support a clearly isolated experimental path:
- production package remains normal,
- an optional compatibility build may use a vendor-compatible identity only for hardware testing,
- never assume `org.codeaurora.snapcam` works on every Qualcomm device,
- never hardcode Snapdragon camera IDs,
- never bypass Android security/hidden APIs/root restrictions.

If a ROM does not expose the sensor publicly to the app identity, report the limitation.

---

## 7. Per-lens configuration

Every useful lens gets an independent `LensUserConfig`.

### Photo settings per lens
- enabled/hidden
- user order
- user display label
- default resolution policy
- aspect ratio
- output format
- RAW/DNG on/off
- Native RAW vs Computational RAW
- quality tier
- HDR Off/Auto/On
- HDR strength
- highlight protection
- shadow recovery
- deghost strength
- temporal denoise
- spatial denoise
- chroma denoise
- sharpness
- texture/microcontrast
- saturation
- vibrance
- warmth
- tint
- color profile
- skin tone protection
- lens shading policy
- distortion correction
- upscaling
- genuine multi-frame super-resolution
- max burst frame count
- flash behavior
- anti-flicker
- exposure bias preference
- focus behavior
- default Pro values
- noise-profile calibration override (advanced)
- black-level override (developer/advanced only)

### Video settings per lens
- preferred resolution
- preferred FPS
- codec
- bitrate mode
- target bitrate
- 8/10-bit
- dynamic range
- color profile / log where available
- stabilization
- OIS/EIS preference
- shutter angle/manual shutter
- ISO
- focus
- white balance
- audio profile
- HDR video
- lens switching allowed/locked
- thermal fallback policy
- forced/experimental combinations
- upscaled-output policy

### Configuration inheritance
Use:
global defaults
→ facing defaults
→ mode defaults
→ per-lens overrides.

Provide "Reset this lens" and "Copy settings to..." actions.

---

## 8. Photo capture architecture

Separate preview from high-quality still capture.

Preferred architecture:

```text
Surface preview
    ↓
scene analyzer + gyro
    ↓
ExposurePlanner
    ↓
shutter
    ↓
short RAW/YUV burst
    ↓
preview restored immediately
    ↓
background processing
    ↓
output
```

On HALs where RAW permanently attached to preview harms timing/quality, use preview-only normal sessions and short RAW capture sessions.

### Zero-shutter-lag direction
Eventually maintain a bounded rolling buffer where hardware allows:
- low-resolution analysis always,
- recent high-quality frames only when memory/throughput safely allows,
- shutter chooses pre/post frames,
- never destabilize preview for theoretical ZSL.

---

## 9. Computational RAW pipeline

Primary quality pipeline:

```text
RAW burst
↓
timestamp/result pairing
↓
dynamic black-level normalization
↓
bad/hot pixel handling
↓
CFA-aware packing
↓
gyro pose estimate
↓
coarse pyramid alignment
↓
local tile alignment
↓
subpixel refinement
↓
rolling-shutter-aware correction where needed
↓
motion/confidence map
↓
noise-aware robust fusion
↓
residual RAW denoiser (only if useful)
↓
optional true multi-frame super-resolution
↓
reconstructed Bayer
↓
DNG
```

### Capture policy
Default computational bursts should usually use constant exposure selected to preserve important highlights.
Bracketed assist frames may be added when scene analysis predicts clipping/dynamic-range benefit.

### Reference-frame score
Do not pick the first or brightest frame blindly.

Score:
- sharpness,
- focus confidence,
- gyro stability,
- highlight safety,
- SNR,
- motion suitability,
- alignment suitability.

### Alignment
Do not stop at global integer translation.

Use:
- gyro initial transform,
- image pyramid,
- local tile motion,
- CFA-safe displacement,
- subpixel refinement,
- per-tile confidence,
- frame rejection.

### Fusion
Weight by:
- alignment confidence,
- motion confidence,
- noise variance,
- saturation,
- exposure,
- temporal consistency.

Use robust statistics so a bad frame cannot destroy the output.

### Neural processing
A neural model is optional and late in the pipeline.
Prefer a tiny residual RAW model:
`clean = fused + residual`.
Do not use diffusion/GAN reconstruction for scientific RAW output.

### Super-resolution
Only call it multi-frame super-resolution when real multiple subpixel observations contribute additional detail.
Interpolation/upscale remains labeled "upscale".

---

## 10. Processed photo pipeline

For non-DNG output:

```text
RAW/YUV source
↓
fusion/denoise
↓
high-quality demosaic
↓
white balance
↓
sensor→working color transform
↓
lens shading/distortion
↓
local tone map
↓
semantic safeguards
↓
texture/sharpening
↓
color profile
↓
HDR gain map / 10-bit path where supported
↓
HEIC / JPEG / Ultra HDR JPEG
```

Never use an 8-bit intermediate for high-quality 10-bit/HDR output.

---

## 11. Night mode

Night mode is a scene-adaptive capture strategy, not just a stronger denoise slider.

Inputs:
- ambient brightness,
- motion,
- gyro stability,
- subject motion,
- OIS,
- lens aperture,
- sensor noise,
- thermal state.

Strategies:
- handheld moving scene: shorter exposures, fewer frames, stronger motion masks,
- handheld static scene: more constant-exposure frames,
- tripod: longer exposures, lower ISO, more frames,
- extreme dark: merge + residual denoise, never invent structure absent from inputs.

Show capture progress only when exposure sequence is genuinely long.

---

## 12. Portrait mode

Preference order:
1. OEM depth/bokeh/Camera Extensions when objectively good and available,
2. physical depth camera if public and useful,
3. stereo/multi-camera depth when geometry supports it,
4. on-device segmentation/depth model fallback.

Preserve the original image and depth/mask metadata when practical.
Do not over-smooth faces.
Per-lens portrait strength and focal preference are configurable.

---

## 13. Pro mode

Expose only supported controls:
- ISO
- shutter
- exposure compensation (auto mode)
- manual focus
- WB temperature/tint
- AF/AE/AWB lock
- histogram
- RGB histogram
- zebras
- focus peaking
- waveform (later)
- false color (later)
- grid
- level
- lens selection
- RAW/processed toggle
- resolution
- color profile

Use logarithmic controls for shutter, ISO, and focus where appropriate.

---

## 14. Panorama

Do not create panorama as a sequence of JPEG screenshots.

Preferred:
- sensor/gyro-guided sweep,
- feature tracking,
- keyframe selection,
- exposure/WB lock after start,
- overlap guidance UI,
- local registration,
- seam finding,
- exposure compensation,
- multi-band blending,
- final high-resolution output.

For high quality, capture high-resolution still/YUV keyframes when the HAL allows it without breaking tracking.

---

## 15. Slo-mo

Use constrained high-speed Camera2 sessions when available.

Expose only native combinations known to work, such as:
- 720p120/240,
- 1080p120/240,
- higher combinations if actually supported.

Optional frame-interpolated slow motion may exist separately and must be labeled "Interpolated".

Never label 60 fps interpolation as native 240 fps.

---

## 16. Time-lapse / long exposure / astro

### Time-lapse
- stable interval scheduler,
- screen-off aware policy where Android allows,
- video or image-sequence mode,
- exposure smoothing,
- optional holy-grail day/night transitions.

### Long exposure
- true long shutter where sensor range supports,
- or computational long exposure from multiple frames for moving water/light trails,
- clearly distinguish real long exposure from stacked effect.

### Astro
- tripod/stability gate,
- long repeated RAW exposures,
- star-aware alignment,
- hot-pixel/fixed-pattern handling,
- sky motion awareness,
- thermal monitoring,
- no fake stars.

---

## 17. Video recording — primary architecture

For highest control, use:

```text
Camera2 capture session
        ↓
MediaCodec input Surface
        ↓
hardware video encoder
        ↓
MediaMuxer
        ↓
MP4
```

This is the primary advanced path because it avoids per-frame CPU copies.

Use CameraX Recorder as a compatibility fallback where the advanced path is broken or an OEM quirk is known.

### Video stream discovery
Build a per-lens capability matrix from:
- StreamConfigurationMap,
- minimum frame durations,
- high-speed configs,
- encoder capabilities,
- MediaCodec profiles/levels,
- dynamic range profiles,
- color space profiles,
- stabilization support,
- concurrent stream constraints.

### Codecs
Prefer based on capability/user setting:
- AVC/H.264 for compatibility,
- HEVC/H.265 for quality/bitrate efficiency,
- AV1 only where hardware encoder support and device stability are proven.

### HDR/10-bit
Use hardware 10-bit paths only when the camera and encoder combination supports them.
Support HLG10/HDR10/HDR10+ when platform metadata and encoder path are valid.
Never convert an 8-bit camera stream into a fake "native 10-bit" claim.

### Audio
Primary:
- AudioRecord → MediaCodec AAC → MediaMuxer.
Add:
- stereo/mono selection,
- mic source selection where public APIs permit,
- wind/noise processing controls where available,
- audio level meter,
- external microphone support.

### Start latency
- preconfigure encoder where practical,
- persistent input surface where supported,
- create session before record tap when safe,
- bounded state machine,
- no UI blocking on muxer finalization.

### Thermal adaptation
Fallback order can reduce:
- bitrate,
- FPS,
- resolution,
- stabilization complexity
before stopping recording.

---

## 18. "Force 4K" / unsupported combinations

User may enable an advanced "Experimental combinations" option.

Policy:

1. If 3840×2160 is advertised and encoder supports it: try native 4K.
2. If metadata is ambiguous but session creation may work: attempt guarded experimental session.
3. If native 4K session fails: fall back to the configured chain.
4. If user explicitly requests "4K output even without native 4K":
   - record best native stream,
   - optionally upscale during or after encode,
   - label metadata/UI as `Upscaled 4K`.
5. Never claim the sensor captured native 4K when it did not.

Same rule applies to FPS.

---

## 19. Video fallback resolver

Create `VideoStrategyResolver`.

Inputs:
- selected lens,
- requested resolution,
- requested FPS,
- codec,
- bit depth,
- dynamic range,
- stabilization,
- concurrent lens switching,
- thermal state.

Output:
- exact stream size,
- exact FPS range,
- encoder,
- profile/level,
- bitrate,
- stabilization,
- fallback reason.

Example fallback:
`4K60 HEVC10 HDR + EIS`
→ `4K30 HEVC10 HDR`
→ `4K30 HEVC SDR`
→ `1080p60 HEVC SDR`
→ `1080p30 AVC`.

Allow the user to choose strict mode ("fail instead of fallback") or automatic fallback.

---

## 20. Seamless video lens switching

Best case:
- use a logical camera and continuous zoom ratio,
- let HAL switch physical cameras internally.

If manual physical routing is needed:
- only promise seamless switching when public APIs + concurrent-camera support make it reliable,
- prewarm second camera/encoder path where possible,
- match exposure/WB/color,
- synchronize timestamps,
- crossfade only if necessary.

If hard switching would corrupt recording:
- disable lens switching for that combination or clearly restart segment recording and merge later.

---

## 21. Color science

Per physical lens calibrate:
- black level,
- white level,
- noise,
- color matrices,
- white-balance behavior,
- lens shading,
- distortion.

Allow profiles:
- Natural
- Vivid
- Warm
- Cool
- Flat
- Log-like processed video profile where technically valid
- Custom

Do not copy Apple/Google proprietary color matrices.
Target perceptual qualities, not copied proprietary data.

---

## 22. Hardware-adaptive processing

Create `ComputeCapabilityManager`.

Probe:
- CPU cores/architecture,
- memory,
- GPU,
- Vulkan,
- NN accelerator delegates,
- storage speed,
- thermal state,
- RAW throughput.

Select tiers dynamically.

Example:
- Tier 0: 2–3 frames, sparse local align, NEON fusion.
- Tier 1: 3–5 frames, local alignment, tiny residual model.
- Tier 2: 5–8 frames, dense alignment, GPU residual model.
- Tier 3: 7–12 frames, best local/subpixel alignment, optional true MFSR.

Scene difficulty can lower or raise work inside the tier.

---

## 23. Performance principles

- Camera callbacks never do heavy work.
- Main thread never performs image processing.
- Reuse buffers.
- Prefer direct/native buffers.
- Avoid RAW → ByteArray → FloatArray → tensor copy chains.
- File-backed mmap is a valid low-memory fallback.
- Use bounded queues.
- Never allow unlimited processing jobs.
- Separate shutter acknowledgement from file completion.
- Measure camera open, first frame, focus, capture, processing, save, lens switching, record start, dropped frames, memory, thermals, battery.

---

## 24. Data integrity

Photo/video must not be lost if:
- app backgrounded after shutter,
- orientation changes,
- camera temporarily disconnects,
- encoder stalls,
- processing fails.

Use atomic temporary files / MediaStore pending rows where appropriate.
Finalize only after successful write.
Clean abandoned temporary files on next launch.

---

## 25. Internal gallery

Eventually include:
- photo/video thumbnail,
- DNG preview,
- compare Native vs Computational,
- metadata viewer,
- delete/share/favorite,
- processing status,
- before/after crop for developer builds.

Do not build a huge media manager before capture is stable.

---

## 26. Settings UX

Settings hierarchy:
- General
- Photo
- Video
- Pro
- Modes
- Lenses
- Color
- Performance
- Storage
- Privacy
- Experimental
- Developer

Per-lens page:
- identity/role/order,
- photo tuning,
- video tuning,
- calibration,
- reset/copy settings.

Include searchable settings once the list becomes large.

---

## 27. Privacy

- Camera works fully offline.
- No cloud processing required.
- Location tagging off by default unless user enables it.
- Remove location on share option.
- No hidden analytics in core camera engine.
- Explain microphone/location permissions when requested.

---

## 28. Testing

### Unit tests
- valuable-lens resolver,
- lens stable identity,
- config inheritance,
- fallback resolvers,
- exposure planner,
- frame selection,
- color math,
- DNG metadata logic,
- video strategy selection.

### Device tests
At least:
- low-end device,
- mid-range device,
- modern flagship,
- Qualcomm/Xiaomi auxiliary-camera case,
- standards-based logical multi-camera device.

### RAW corpus
Maintain deterministic RAW burst fixtures for:
- daylight,
- indoor,
- high ISO,
- motion,
- highlights,
- foliage,
- text,
- skin,
- extreme dark.

### Video matrix
Test per lens:
- all claimed resolutions,
- all claimed FPS,
- codec combinations,
- stabilization,
- HDR/10-bit,
- start/stop loops,
- 10+ minute thermals,
- storage nearly full,
- background/interruptions.

---

## 29. Benchmarks

Photo:
- preview FPS,
- capture acknowledgement,
- RAW acquisition duration,
- alignment,
- fusion,
- denoise,
- DNG write,
- HEIC/JPEG encode,
- memory peak.

Video:
- record-start latency,
- dropped frames,
- encoder queue,
- audio drift,
- bitrate accuracy,
- temperature over time,
- battery drain,
- lens-switch interruption.

Quality:
- SNR,
- PSNR/SSIM on controlled corpus,
- MTF/detail,
- ghosting,
- color ΔE,
- highlight clipping,
- shadow recoverability,
- temporal stability,
- video motion/detail/noise.

---

## 30. Phased implementation

### Phase 0 — Foundation
Build/CI/lint/tests/app shell.

### Phase 1 — Discovery
Camera graph, valuable lenses, diagnostics.

### Phase 2 — Reliable Photo Preview
Surface preview, orientation, focus, AE, zoom, lifecycle.

### Phase 3 — Lens Manager
Enable/hide/order/rename/user-confirmed role/zoom labels.

### Phase 4 — Basic Still Capture
JPEG/HEIC/RAW/DNG, MediaStore, orientation, thumbnail.

### Phase 5 — Pro Foundation
ISO, shutter, focus, WB, histogram, zebras, peaking.

### Phase 6 — Video Foundation
Camera2→MediaCodec→MediaMuxer, AVC/HEVC, audio.

### Phase 7 — Advanced Video
4K/FPS matrix, 10-bit/HDR, stabilization, fallback, high-speed.

### Phase 8 — Per-Lens Settings
Full photo/video override system.

### Phase 9 — Computational Capture Foundation
Burst buffer, timestamps, gyro, frame selection, C++ processing abstraction.

### Phase 10 — HDR
constant-exposure burst, highlight assist, local alignment, deghost, tone map.

### Phase 11 — Night
adaptive exposure sequences and motion-aware temporal denoise.

### Phase 12 — Residual RAW Denoise
tiny hardware-adaptive model only after classical fusion is strong.

### Phase 13 — Genuine Multi-frame SR
subpixel reconstruction, strict quality gate.

### Phase 14 — Portrait
OEM/depth/ML fallback.

### Phase 15 — Panorama
sensor-guided stitching.

### Phase 16 — Time-lapse / Long Exposure / Astro
validated advanced capture.

### Phase 17 — iOS-26 polish
animations, haptics, accessibility, mode transitions, lens switching polish.

### Phase 18 — Device tuning
quirk database, performance/thermal optimization, release hardening.

---

## 31. Definition of final success

The project is successful only when:
- important lenses are correctly exposed and controllable,
- preview is stable,
- focus/tap mapping is correct,
- capture is reliable,
- photo quality is measurably better than naive single-frame capture,
- computational modes do not hallucinate detail,
- video modes produce the exact claimed stream/codec/FPS or clearly show fallback,
- per-lens settings persist correctly,
- low-end phones remain usable,
- flagship phones use more of their available hardware,
- modes are honest,
- output metadata is correct,
- long recordings and repeated captures survive thermal/memory stress.

The goal is not to add the largest number of toggles.
The goal is a camera that is **fast, predictable, configurable, and produces excellent media**.
