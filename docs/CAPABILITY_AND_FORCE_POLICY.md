# Capability and Force Policy

There are three states:

1. **Supported** — metadata + session/capture validation prove it.
2. **Experimental** — metadata is incomplete/contradictory; guarded attempt is allowed.
3. **Unsupported** — the HAL/encoder cannot provide it.

The UI may let advanced users attempt Experimental combinations.

Unsupported native capabilities cannot be made real by software.

Examples:
- no RAW capability → cannot produce true sensor RAW;
- no 4K stream → cannot capture native 4K;
- no 120 fps high-speed mode → cannot capture native 120 fps;
- hidden physical camera not public to app → cannot be guaranteed.

Derived outputs are allowed if honestly labeled:
- upscaled 4K,
- interpolated slow motion,
- computational long exposure,
- computational RAW.
