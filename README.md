# Nothing Light

A minimal Android BLE controller for MR Star / GATT--DEMO RGB strips.

## Features
- Pitch-black Nothing-inspired UI
- HSV color wheel
- Brightness slider
- ON/OFF control
- Automatic scan for GATT--DEMO
- Direct BLE control; no cloud and no MR Star dependency

## Protocol
The app uses the reverse-engineered MR Star GATT protocol:
- Write characteristic: `0000fff3-0000-1000-8000-00805f9b34fb`
- Frame prefix: `BC`
- Frame terminator: `55`
- Colors are sent as HSV.

## Build
Open this folder in Android Studio and build/install the debug APK.

The app requests Bluetooth permissions on first launch. On Android 12+, allow nearby-device access.
