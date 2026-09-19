# Nothing Light v1.6

Stability-focused MR Star / GATT-DEMO controller.

The previous v1.5 archive was not actually patched and still contained the guessed effect implementation. This release is rebuilt from the known-working v1.4 project and changes only the effect implementation.

Actual MR Star protocol:
- Effect: `BC 06 02 EFFECT_HI EFFECT_LO 55`
- Speed: `BC 08 01 SPEED 55`

Source: https://github.com/mishamyrt/mr-star-ble

Usable modes:
- Solid
- Rainbow Strobe — 7
- Rainbow Gradient — 10
- Colorful Energy — 3
- Colorful Jumps — 4
- Rainbow Flow — 55
- Rainbow Trail — 76
- Rainbow Run — 91

Solid mode does not send a guessed effect command; it restores the selected static color.

All other working v1.4 features are retained.
