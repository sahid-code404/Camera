# Project State

## Current Phase

Phase 0 + Phase 1 active on `phase/01-foundation-discovery`.

## Completed

- New repository skeleton created.
- App name fixed to `Camera`.
- Android/Compose bootstrap source added.
- Core architecture contracts added.
- Full product/build specification documented.
- Photo/video/UI/per-lens configuration strategies documented.
- Prior Universal-Camera lessons captured.
- Official Gradle 9.5 wrapper committed.
- GitHub Actions Android CI enabled.
- Canonical Phase 0/1 development branch created.
- Public Camera2 metadata enumeration implemented.
- Logical/physical camera route modeling implemented.
- Camera stream/capability discovery implemented for RAW, JPEG, HEIC, Ultra HDR JPEG, YUV, PRIVATE and constrained high-speed modes where exposed.
- Valuable-lens resolver foundation implemented without hard-coded numeric camera-ID assumptions.
- Camera route open/session probing implemented so metadata-only routes are not automatically trusted.
- Validated catalog filters failed routes before presenting default valuable lenses.
- Diagnostics JSON foundation implemented.
- Per-lens custom zoom labels added to the domain model and specification.
- Numeric zoom anchor and user-visible zoom text are explicitly separated so labels never alter optical/zoom math.
- Lens Manager UI implemented for visibility, order, role confirmation, custom name and custom zoom label.
- Lens Manager persistence implemented with DataStore.
- Unit tests for valuable-lens resolution are passing in CI.
- Android lint passes in CI.
- Debug APK assembles and uploads successfully in CI.
- Green Phase-1 foundation CI run: GitHub Actions run `32342060053`, head commit `4ea467042ea43164c12a6c5c7fc9173e41737f20`.

## Not Implemented Yet

- Real live Camera2 preview.
- Real preview lens switching.
- Tap-to-focus / metering coordinate mapping.
- Pinch zoom and continuous zoom controller.
- Still capture.
- RAW/DNG capture.
- JPEG/HEIC/Ultra HDR capture.
- Computational photography.
- Video recording.
- Portrait/Night/Pro/Slo-mo/Panorama/Time-lapse/Astro.
- Device benchmarks.
- Physical-device validation of discovered routes.

## Current Work

Finish Phase 1 on real hardware:
1. install the green Phase-1 debug APK,
2. grant Camera permission,
3. record discovered and session-validated routes,
4. verify valuable-lens classification and Lens Manager behavior,
5. verify custom zoom labels/order/visibility persist,
6. record any OEM auxiliary-camera limitations or quirks.

Then begin Phase 2:
1. real edge-to-edge Camera2 preview,
2. lifecycle-safe session controller,
3. orientation/aspect transform model,
4. exact tap-to-focus/metering mapping,
5. pinch zoom and user-confirmed lens switching,
6. preview performance instrumentation.

## CI Status

- Unit tests: PASS
- Lint: PASS
- Debug APK assembly: PASS
- APK artifact upload: PASS

## Non-negotiable Quality Rule

No user-visible mode is marked as working until the underlying camera path is actually implemented and validated.
