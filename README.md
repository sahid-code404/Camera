# Camera

A from-scratch Android camera project focused on **maximum image quality, maximum hardware coverage, low latency, and deep per-lens configurability**.

The app name is intentionally simple: **Camera**.

This repository is a bootstrap/handoff package. It contains:
- a real Android/Gradle project skeleton,
- a compile-oriented Compose UI shell,
- architecture/domain interfaces,
- detailed photo/video/computational-photography specifications,
- an iOS-26-inspired interaction/UI specification using original assets,
- a phase-by-phase engineering roadmap,
- test/benchmark requirements,
- lessons learned from the previous Universal-Camera project.

## Core product goals

- One serious camera app across low-end, mid-range, and flagship Android devices.
- Camera2-first low-level control with CameraX used selectively for compatibility/fallback.
- Discover all publicly exposed cameras and logical/physical relationships.
- Optional vendor-compatibility experiments for ROMs that expose extra cameras based on package identity.
- User decides which lenses are visible and their order.
- Per-lens settings for photo and video.
- Fast, clean, iOS-26-inspired camera UX with smooth zoom/lens animations.
- Photo, Video, Portrait, Night, Pro, Slo-mo, Panorama, Time-lapse, Long Exposure, Burst/Best Shot, and Astro as real implementations only.
- Computational RAW, HDR, denoise, Night, and genuine multi-frame super-resolution.
- Camera2 + MediaCodec + MediaMuxer video pipeline for high control and zero-copy recording where possible.
- Honest capability reporting and graceful fallback.

## Important truth about "forced" modes

The app may provide an **Experimental/Force** option that attempts non-default combinations, but software cannot force a camera HAL to output a stream it fundamentally does not expose. For example, true 3840×2160 capture cannot be guaranteed if the HAL has no 4K stream. If a user requests 4K on such hardware, Camera can:
1. attempt safe experimental configurations,
2. fall back to the best native stream,
3. optionally upscale the final video to 4K **only if explicitly labeled as upscaled**.

The app must never call upscaled 1080p "native 4K".

## First handoff

Read these files in order:

1. `HANDOFF.md`
2. `PROJECT_STATE.md`
3. `MASTER_BUILD_SPEC.md`
4. `docs/REUSE_FROM_UNIVERSAL_CAMERA.md`
5. `docs/ROADMAP.md`

Do not implement every mode at once. Build and validate the camera foundation first.
