# Photo Pipeline

## Core rule

The primary Photo pipeline is **RAW-first whenever the selected lens exposes Camera2 RAW capability**.

JPEG/HEIC/Ultra HDR are final delivery encodings or explicit fallbacks; they are not the source data for the computational master.

## Fast path
- preview analysis at low cost,
- shutter acknowledgement immediately,
- acquire sensor frames,
- restore preview as soon as acquisition finishes,
- background RAW processing,
- render/export after fusion.

## Native RAW
`RAW_SENSOR + TotalCaptureResult → DngCreator → DNG`.

## Computational RAW
`RAW burst → timestamp/result pairing → dynamic black-level normalization → CFA-safe staging → reference selection → alignment → motion/confidence map → robust Bayer fusion → optional residual RAW denoise → optional true multi-frame SR → fused Bayer master`.

The current first fusion implementation is intentionally conservative: same-exposure burst, Bayer-safe even-pixel alignment and robust motion rejection. Local/sub-pixel alignment and true MFSR are subsequent upgrades; do not fake them with interpolation.

## DNG
- Native RAW DNG stores a real sensor RAW frame.
- Computational RAW DNG stores the fused Bayer master when its dimensions/metadata are valid for `DngCreator`.
- DNG remains full sensor aspect; global `1:1 / 4:3 / 16:9` composition does not throw away RAW data.

## Processed photo
`fused RAW master → high-quality demosaic → WB → sensor-to-working color → lens correction → local tone/HDR → semantic safeguards → texture/sharpening → color profile → output transform → HEIC/JPEG/Ultra HDR`.

The processed photo uses the camera-wide selected composition aspect. DNG is an optional RAW master/sidecar, not the final rendered photo.

## Rules
- constant-exposure burst is the normal computational default,
- add bracketed assist frames only when highlight analysis justifies them,
- dynamic black level when available,
- noise profile per frame,
- no 8-bit master path,
- no fake resolution,
- no generative texture for RAW,
- motion masks/confidence rejection required for multi-frame merge,
- reference frame selected by quality, not brightness alone,
- true super-resolution only when multiple sub-pixel observations provide real extra information,
- if a lens exposes no RAW path, use an explicitly labeled YUV/OEM processed fallback rather than pretending it is Computational RAW.
