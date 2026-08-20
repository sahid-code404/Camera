# Project State

## Current Phase

Phase 1 hardware discovery is validated enough to support active Phase 2/3 camera and RAW-photo work on `phase/01-foundation-discovery`.

## Implemented and CI-validated

- App name `Camera` with Compose shell.
- Official Gradle 9.5 wrapper and Android CI.
- Public Camera2 metadata enumeration.
- Logical/physical camera route graph.
- RAW/JPEG/HEIC/YUV/PRIVATE/high-speed capability discovery where exposed.
- Valuable-lens filtering without hard-coded numeric camera IDs.
- SnapCam-compatible development identity for vendor auxiliary-camera exposure.
- Camera route open/session probing.
- Lens Manager: visibility, order, role, custom name and custom zoom label.
- Persistent lens configuration.
- Real Camera2 live preview.
- Rear/front lens switching using only valuable photographic routes.
- Camera ownership recovery when another camera application temporarily takes the device.
- Sharp `1:1` preview by cropping a high-quality native stream rather than enlarging a tiny square stream.
- Camera-wide Photo composition aspect: default `4:3`, optional `1:1` and `16:9`; the selected aspect persists across every lens and facing.
- Unmirrored front preview.
- Tap-to-focus and AF/AE metering regions.
- Pinch zoom using Camera2 zoom ratio or crop fallback.
- Computational RAW processing module separated from Camera2 session ownership.
- File-backed RAW16 staging with row/pixel-stride handling.
- Sensor-timestamp pairing between RAW images and capture metadata.
- Dynamic black-level use when available with static-black-level fallback.
- Four-frame constant-exposure RAW acquisition foundation.
- Physical-camera capture-result selection for auxiliary lenses when Android exposes it.
- Conservative Bayer-safe RAW alignment, motion rejection and robust multi-frame fusion.
- Fused native-resolution Bayer master output.
- Fused computational DNG writing through `DngCreator`.
- Primary shutter now chooses the computational RAW path whenever the selected lens exposes Camera2 RAW capability.
- JPEG capture remains only an explicit compatibility fallback for lenses without a public RAW path.
- Preview is restored after RAW acquisition while fusion/DNG writing continues on the processing thread.

Latest green RAW-first integration CI:
- head: `b42a2b84360e35aea9707a65bb9506fb006de11f`
- GitHub Actions run: `32353822467`
- unit tests: PASS
- lint: PASS
- debug APK assembly: PASS
- artifact upload: PASS

## Important current boundary

The computational RAW foundation is **not yet the finished flagship photo pipeline**.

Implemented now:
`RAW burst -> timestamp/result pairing -> black-level normalization -> Bayer-safe alignment -> motion rejection -> robust fusion -> fused Bayer master -> DNG`

Still to implement before calling the Photo pipeline complete:
- better reference-frame scoring using focus/motion/highlight/SNR signals,
- gyro-assisted initialization,
- local/tile alignment,
- sub-pixel alignment,
- rolling-shutter-aware correction where necessary,
- stronger noise-model-aware fusion,
- optional residual RAW denoiser,
- genuine multi-frame super-resolution using real sub-pixel observations,
- high-quality demosaic,
- white balance and sensor-to-working-space color calibration,
- lens correction,
- local HDR/tone mapping,
- skin/semantic safeguards,
- texture/sharpening/color profiles,
- final HEIC/JPEG/Ultra HDR renderer generated from the fused RAW master.

DNG is the RAW master/sidecar, not the final rendered photo. The global `1:1 / 4:3 / 16:9` composition should crop the processed output while preserving the full sensor Bayer data in DNG.

## Next work

1. Real-device validate the new four-frame RAW acquisition and DNG output on every RAW-capable valuable lens.
2. Add RAW acquisition diagnostics: frame count, timestamps, exposure/ISO consistency, physical route, dimensions and failure reason.
3. Upgrade fusion from global Bayer-safe translation to local/sub-pixel alignment.
4. Build the processed-photo renderer from the fused RAW master.
5. Add genuine MFSR only after sub-pixel evidence and confidence checks are reliable.
6. Then add adaptive HDR/Night capture planning on top of the same RAW primitives.

## Later work

- HEIC / Ultra HDR final output.
- Pro controls.
- Video engine.
- Portrait / Night / Slo-mo / Panorama / Time-lapse / Long Exposure / Astro.
- iOS-26 interaction polish and smooth lens-switch animation.
- Device tuning, thermal policy and release hardening.

## Non-negotiable quality rules

- Never use JPEG/YUV as the computational master when a trustworthy RAW path is available.
- Never label interpolation/upscaling as true sensor resolution or true multi-frame super-resolution.
- Never mark a mode complete until its underlying camera path is implemented and physically validated.
