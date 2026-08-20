# Project State

## Current Phase

Bootstrap / pre-Phase-0.

## Completed

- New repository skeleton created.
- App name fixed to `Camera`.
- Android/Compose bootstrap source added.
- Core architecture contracts added.
- Full product/build specification documented.
- Photo/video/UI/per-lens configuration strategies documented.
- Prior Universal-Camera lessons captured.
- CI bootstrap workflow added.

## Not Implemented Yet

- No real Camera2 preview.
- No camera enumeration.
- No valuable-lens resolver.
- No user lens ordering persistence.
- No still capture.
- No RAW/DNG.
- No JPEG/HEIC/Ultra HDR.
- No computational photography.
- No video recording.
- No Portrait/Night/Pro/Slo-mo/Panorama/Time-lapse/Astro.
- No device benchmarks.
- No physical-device validation.

## Next Phase

Phase 0 + Phase 1:
1. stabilize build/CI,
2. implement public camera discovery,
3. implement logical/physical graph,
4. implement valuable-lens resolver,
5. implement diagnostics and JSON export,
6. validate on at least one real device.

## Non-negotiable Quality Rule

No user-visible mode is marked as working until the underlying camera path is actually implemented and validated.
