# MeshLink — Offline Bluetooth Mesh Emergency Communication

Native Kotlin + Jetpack Compose Android prototype. The offline path uses only BLE, Room/SQLite, Android Keystore, local GNSS and local audio.

## Build
Open this directory in Android Studio with the required Android SDK. The included Gradle wrapper is configured for Gradle 9.6.

## Offline requirement
For the physical demo, disable Wi-Fi and mobile data on every device. Internet is not required for mesh discovery, GATT transport, routing, persistence, SOS, location or voice transfer.

## Android permissions
BLE permissions are requested for mesh mode. Location is requested for GPS features, microphone for voice, and notifications on supported Android versions.

## Physical validation
Follow `docs/testing.md`. BLE behavior must be verified on real Android hardware.


## 2.6 Reliability updates
- Profile name is MeshLink identity metadata carried by authenticated HELLO, not the handset Bluetooth name.
- Profile avatar is local UI metadata.
- Voice assets are stored per message ID and playback is independent per asset.
- SOS/voice ordering is tolerant: if the voice completes before SOS metadata, the SOS card resolves the stored voice asset when it arrives.
- Offline path remains BLE-only; no simulated nodes, coordinates, distances, or cloud relay were added.
