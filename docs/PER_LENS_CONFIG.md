# Per-Lens Configuration Model

Stable key:
`LensStableId = facing + logical/physical identity + focal length bucket + sensor-size bucket + route signature`.

Never use the current list index as identity.

## User-facing lens metadata
- visible
- position/order
- role
- custom name
- numeric display zoom anchor used by zoom math
- optional custom zoom label used only for presentation
- favorite/default

### Custom zoom label

The user may independently choose the text rendered on each lens pill.

Examples:
- `0.6×`
- `1×`
- `2.5×`
- `24mm`
- `35mm`
- `MAIN`
- `TELE`
- another short custom label

The custom label is **presentation-only**. It must never alter:
- focal length,
- physical camera selection,
- zoom-ratio math,
- lens-switch thresholds,
- EXIF/DNG metadata,
- native optical capability.

Keep `displayZoomAnchor: Float?` and `customZoomLabel: String?` separate. If a custom label is empty/unset, format the numeric anchor automatically. Settings UI should preview the label exactly as it will appear on the camera screen. Long labels should be normalized/truncated for the compact lens pill while the full lens name remains available in Lens Manager.

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
