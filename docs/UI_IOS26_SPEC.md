# iOS-26-Inspired Camera UI Specification

Goal: reproduce the **interaction density, spatial balance, fluidity, and visual hierarchy** of the iOS 26 camera experience while using original assets.

## Main Photo screen

Top:
- flash pill/circle,
- RAW/HDR indicator when relevant,
- aspect/format quick control,
- overflow control.

Center:
- full viewfinder.
- tap-focus indicator with exposure drag.
- grid/level overlays.

Above shutter:
- animated lens pills generated from user-confirmed lens layout.
- selected lens grows slightly.
- zoom value interpolates smoothly during pinch.
- haptic at optical lens anchors.

Bottom:
- gallery thumbnail,
- large shutter,
- camera flip,
- mode carousel.

## Liquid glass
Use:
- translucent blurred containers,
- bright edge highlight,
- subtle inner shadow,
- scale + blur + alpha transitions,
- no heavy persistent panels over the viewfinder.

## Mode transitions
Horizontal swipe:
- mode strip translates,
- selected text increases emphasis,
- capture controls morph,
- viewfinder should not fully rebind unless camera stream requirements change.

## Lens switching
Prefer:
1. logical-camera seamless zoom,
2. physical output selection inside logical camera,
3. direct camera switch.

If direct device switching is required:
- preserve last viewfinder frame briefly,
- crossfade into new stream,
- avoid pretending the switch was continuous if exposure/FOV jumps.

## Accessibility
- all glass controls have semantic labels,
- large touch targets,
- haptics optional,
- reduced-motion option,
- high-contrast UI option.
