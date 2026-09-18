# Nothing Light v1.5

Minimal MR Star / GATT-DEMO BLE controller.

### v1.5 — real MR Star effects
The previous effect implementation used guessed packet contents. v1.5 replaces it with the actual MR Star protocol from the open-source `mr-star-ble` implementation:

- Effect command: `BC 06 02 EFFECT_HI EFFECT_LO 55`
- Speed command: `BC 08 01 SPEED 55`
- Uses real MR Star effect IDs rather than UI indexes.
- Includes 26 documented MR Star effects.
- Retains the centered connection UI, Nothing-style controller, UI scaling, presets, brightness and color-temperature control.

Effect IDs are based on the `Effect` enum in:
https://github.com/mishamyrt/mr-star-ble
