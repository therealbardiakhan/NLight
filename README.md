# Nothing Light v1.2

Minimal Android BLE controller for MR Star / GATT-DEMO LED strips.

## v1.2 changes
- Status-bar-safe top layout using Android window insets.
- More generous side margins and touch targets.
- Larger, smoother brightness slider with a wide invisible touch area.
- Eight quick preset colors below the color wheel.
- Responsive sizing using Android dp units.

## MR Star protocol
Service: `00002022-0000-1000-8000-00805f9b34fb`
Write characteristic: `0000fff3-0000-1000-8000-00805f9b34fb`
Frame format: `BC | command | argument length | arguments | 55`
