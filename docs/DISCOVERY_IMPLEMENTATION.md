# Phase 1 Discovery Implementation

Current branch: `phase/01-foundation-discovery`.

Implemented foundation:
- normalized Android-free camera capability models,
- Camera2 metadata scanning,
- logical/physical camera route graph,
- standard + maximum-resolution output-size collection,
- RAW/manual/burst/reprocess/high-speed capability collection,
- optical metadata and 35 mm-equivalent estimation,
- valuable-lens resolver based on optics instead of Camera2 numeric IDs,
- duplicate suppression when the same physical sensor is also directly enumerated,
- logical aggregator hiding when useful physical members are available,
- default zoom-anchor estimation from optical field of view,
- diagnostics JSON generation,
- bootstrap UI wired to show discovered valuable lenses,
- unit tests for resolver behavior.

Still required before Phase 1 is complete:
- runtime camera permission flow,
- actual openability/session probe,
- Lens Manager persistence,
- diagnostics export/share UI,
- real-device validation and profile capture,
- CI fixes until tests/lint/APK build are green.
