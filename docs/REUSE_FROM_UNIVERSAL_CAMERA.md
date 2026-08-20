# Reuse From Universal-Camera — Proven Lessons

This new project is from scratch, but it should reuse engineering knowledge already learned.

## 1. Valuable cameras, not raw IDs

The previous project demonstrated that a ROM can expose many Camera2 IDs that do not equal independent useful lenses.

New design:
`CameraManager IDs → route graph → open/session probe → optical uniqueness → ValuableCameraRoute`.

Do not display every ID to users.

## 2. Auxiliary-camera package identity

On at least one previous Xiaomi/Qualcomm test device:
- the normal app identity exposed only the usual main/front routes,
- a Snapcam-compatible package identity exposed additional Camera2 routes.

This proves that package allowlisting can matter on some ROMs.

It does **not** prove:
- all Snapdragon phones behave the same,
- all extra IDs are useful,
- production should permanently use a vendor package name.

Keep this as an isolated compatibility experiment.

## 3. Logical/physical cameras

A logical camera may contain physical camera IDs.
A logical route should usually be infrastructure, not another user lens.

Physical members may become user lenses if:
- optical metadata is distinct,
- public APIs allow routing,
- session creation works,
- photo/video output is useful.

## 4. Preview session topology matters

The previous app observed that some HALs produced worse preview exposure/timing when a large RAW surface was permanently configured.

Preferred architecture on affected devices:
- normal preview session = processed Surface only,
- shutter = temporarily create RAW capture session,
- capture burst,
- immediately restore preview.

Make this behavior capability/quirk-driven instead of globally hardcoded.

## 5. Timestamp pairing

RAW images and `TotalCaptureResult` must be paired by sensor timestamp.
Do not assume callbacks arrive in the same order.

## 6. Camera-critical stage vs processing stage

Capture should finish quickly.
Alignment, fusion, denoise, DNG writing, and preview generation should be queued after frames are safely staged.

## 7. File-backed RAW is useful

A 12 MP RAW16 frame is roughly 24 MB.
Large bursts can consume hundreds of MB.

A file-backed/mmap path is valuable on memory-constrained phones.
High-end devices may use pooled direct memory instead.

## 8. Global integer alignment is not enough

Previous native RAW fusion used global dx/dy translation.
The new engine must move toward:
- gyro initialization,
- local tiles,
- subpixel registration,
- rolling-shutter tolerance,
- confidence maps.

## 9. Do not fake super-resolution

Interpolating each Bayer plane to a larger size is upscaling, not genuine multi-frame super-resolution.

Keep:
- `Upscaled output` and
- `True multi-frame SR`
as separate features.

## 10. DNG correctness beats clever patching

Prefer native-dimension Bayer output compatible with `DngCreator`.
Only use a custom DNG writer when necessary, with explicit metadata validation.

## 11. Build one canonical development line

The previous repository accumulated many parallel Phase-2 branches.
This new project should keep one active phase branch plus short-lived experiments.
