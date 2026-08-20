# Per-Lens Configuration Model

Stable key:
`LensStableId = facing + logical/physical identity + focal length bucket + sensor-size bucket + route signature`.

Never use the current list index as identity.

## User-facing lens metadata
- visible
- position/order
- role
- custom name
- display zoom anchor
- favorite/default

## Photo profile
- output format
- aspect ratio
- resolution policy
- Native/Computational RAW
- HDR
- highlight/shadow
- denoise
- sharpness
- texture
- saturation/vibrance
- warmth/tint
- color profile
- upscaling
- true SR
- flash
- anti-flicker
- quality tier

## Video profile
- resolution
- FPS
- codec
- bitrate
- bit depth
- HDR/dynamic range
- stabilization
- audio profile
- manual controls
- lens-switch policy
- fallback policy
- experimental combos

## Inheritance
`Global → Mode → Lens → Temporary session override`.

Every settings screen must show whether a value is inherited or explicitly overridden.
