# Photo Pipeline

## Fast path
- preview analysis at low cost,
- shutter acknowledgement immediately,
- acquire sensor frames,
- restore preview,
- background processing.

## Native RAW
`RAW_SENSOR → DngCreator → DNG`.

## Computational RAW
`RAW burst → local alignment → robust Bayer fusion → optional residual denoise → DNG`.

## Processed
`RAW/YUV → fusion → demosaic → WB/color → local tone → texture → HEIC/JPEG/Ultra HDR`.

## Rules
- dynamic black level when available,
- noise profile per frame,
- no 8-bit master path,
- no fake resolution,
- no generative texture for RAW,
- motion masks required for multi-frame merge,
- reference frame selected by quality, not brightness alone.
