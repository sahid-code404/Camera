# Video Pipeline

## Primary path
`Camera2 Surface → MediaCodec hardware encoder → MediaMuxer MP4`.

Use a Surface encoder path to avoid per-frame CPU copies.

## Fallback
CameraX Recorder may be used on devices where the direct advanced path is unreliable.

## Capability matrix
For each lens, validate:
- size,
- FPS,
- codec,
- bit depth,
- HDR profile,
- stabilization,
- concurrent lens switching.

Do not infer a valid combination from each feature independently.

## Force mode
Experimental settings may attempt ambiguous combinations.
If session/encoder configuration fails, use a documented fallback chain.

If the user wants 4K output but the camera only provides 1080p:
- record native 1080p,
- optional upscaled 4K encode,
- label as upscaled.

## Quality
- hardware HEVC for quality where stable,
- AVC compatibility option,
- 10-bit only when source + encoder path is truly 10-bit,
- bitrate based on resolution/FPS/content, not one global constant,
- audio drift tests required,
- long-duration thermal tests required.
