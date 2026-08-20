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
- CI bootstrap workflow added.
- Canonical Phase 0/1 development branch created.
- Per-lens custom zoom labels added to the domain model and specification.
- Numeric zoom anchor and user-visible zoom text are explicitly separated so labels never alter optical/zoom math.

## Not Implemented Yet

- Real Camera2 preview.
- Camera enumeration.
- Valuable-lens resolver.
- User lens ordering/visibility/custom-label persistence.
- Still capture.
- RAW/DNG.
- JPEG/HEIC/Ultra HDR.
- Computational photography.
- Video recording.
- Portrait/Night/Pro/Slo-mo/Panorama/Time-lapse/Astro.
- Device benchmarks.
- Physical-device validation.

## Current Work

Phase 0 + Phase 1:
1. stabilize build/CI,
2. implement public camera discovery,
3. implement logical/physical graph,
4. implement valuable-lens resolver,
5. implement diagnostics and JSON export,
6. implement Lens Manager persistence for visibility/order/role/custom name/custom zoom label,
7. validate on at least one real device.

## Non-negotiable Quality Rule

No user-visible mode is marked as working until the underlying camera path is actually implemented and validated.
