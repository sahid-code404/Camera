# Engineering Handoff — Camera

## What this repository is

This is the clean starting point for a new, from-scratch Android camera app named **Camera**.

The final goal is a high-performance, high-quality computational camera with:
- iOS-26-inspired interaction and layout,
- deep Camera2 control,
- reliable auxiliary-lens handling,
- per-lens user configuration,
- best-possible photo and video quality,
- device-adaptive processing from low-end to flagship hardware.

This is **not** a completed camera. The bootstrap intentionally does not pretend that unimplemented modes work.

## Reuse previous experience, not previous mistakes

Use the lessons documented in `docs/REUSE_FROM_UNIVERSAL_CAMERA.md`.

Important proven lessons:
- Raw Camera2 IDs are not the same thing as useful photographic lenses.
- Lens roles must be inferred from optics/capabilities, not numeric IDs.
- Logical and physical camera relationships must be modeled.
- Some Qualcomm/Xiaomi ROMs may expose additional routes when the package identity is allowlisted.
- A "vendor compatibility identity" is device/ROM-specific and must remain optional/experimental.
- Some HALs change preview behavior when a large RAW stream is permanently attached.
- Preview-only session + short RAW capture session can be a better architecture on affected devices.
- RAW Images must be paired with TotalCaptureResult by sensor timestamp.
- Expensive fusion should run after the camera-critical capture stage.
- Never call interpolation "super resolution".
- Never claim native 4K, native high FPS, RAW, HDR, or a lens if the HAL cannot actually deliver it.

## Immediate task

Implement **Phase 0 + Phase 1 only** first.

### Phase 0 — production project foundation
- Verify the Gradle/AGP/Kotlin toolchain.
- Replace the bootstrap Gradle downloader with the official Gradle wrapper.
- Ensure debug build, lint, unit tests, and CI are green.
- Keep the app name `Camera`.
- Keep package/application ID configurable. Default production namespace is `com.camera.app`.

### Phase 1 — universal camera discovery
Implement:
- CameraManager ID enumeration.
- CameraCharacteristics extraction.
- hardware-level/capability mapping.
- logical-camera graph.
- physical-camera graph.
- stream configurations.
- RAW/YUV/JPEG/HEIC/Ultra HDR availability.
- manual sensor ranges.
- FPS/high-speed tables.
- dynamic range profiles.
- stabilization.
- zoom ratio / focal length / sensor size / FOV.
- public openability/session validation.
- valuable-lens classification.
- user-facing lens roles with confidence.
- device profile export to sanitized JSON.
- a diagnostics screen.
- fake device profiles and unit tests.

## Do not yet implement
- computational HDR
- Night
- portrait ML
- panorama stitching
- video encoder
- neural denoise
- super-resolution

until camera discovery and session reliability are proven on real devices.

## Source-of-truth rules

1. Hardware capability is the source of truth.
2. Session creation is stronger evidence than metadata alone.
3. A reported output size is not considered usable until a session/capture validation path exists.
4. No hardcoded "camera ID 0 = main, 2 = ultrawide" assumptions.
5. User lens ordering is a preference layer, never a hardware truth.
6. Per-lens settings are keyed to a stable lens identity built from route + physical identity + optical metadata, not list index.
7. Unsupported features must fall back or be disabled honestly.
8. Experimental "force" actions must be labeled and must never fake native capability.

## Definition of done for each phase

Every phase must produce:
- implementation summary,
- changed files,
- architecture changes,
- build result,
- unit-test result,
- lint result,
- device-test result if hardware is required,
- performance measurements where relevant,
- known limitations,
- updated `PROJECT_STATE.md`,
- recommended next phase.

## Branch policy

Use:
- `main` = stable milestones only
- `phase/<number>-<name>` = active milestone
- `experiment/<name>` = risky hardware experiments

Do not create many parallel production branches for the same feature. Merge validated work quickly into one canonical integration line.
