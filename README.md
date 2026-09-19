# Nothing Light v1.7

Built directly from the known-working v1.6.1 project.

## New safe features
- Effect speed slider (1–100%).
- Speed is sent using the documented MR Star speed packet.
- Effect speed is remembered.
- Last color, brightness and color-temperature values are remembered.
- 30-minute sleep timer with a small SLEEP button; tap again to cancel.

## Full feature roadmap / ideas
Safe candidates for future releases:
- Favorite color slots
- Preset editing
- Custom effect speed per effect
- Auto reconnect to the last GATT device
- Connection diagnostics / RSSI
- Smooth brightness transitions
- Double-tap power
- Ambient microphone/music mode (opt-in)
- Sunrise/sunset timer
- Custom scenes
- More exact RGB/HEX entry
- CCT presets
- Battery/status-friendly low-power mode

The current release intentionally avoids adding risky protocol commands or undocumented BLE packets.
