# MeshLink Architecture

MeshLink is an Android-native offline BLE system. The foreground service owns discovery, GATT server/client links, routing, queueing and emergency events; Compose observes state through `MeshEngine`.

## Transport
- BLE advertiser + filtered scanner using the MeshLink service UUID.
- GATT server RX write / TX notification characteristics.
- MTU request to 247 and application framing for packets larger than one GATT write.
- No Wi-Fi, HTTP, WebSocket, Firebase or cloud relay.

## Mesh
Controlled flooding is used for the first release. Every packet has a UUID, origin/sender IDs, destination, priority, TTL and hop count. Receivers authenticate/decrypt, apply replay/dedup/safety policy, persist before forwarding, and decrement TTL/increment hop count.

## Persistence
Room stores messages/queue, peers, event audit records and voice assets. Queue is bounded and expired items are pruned.

## Security
Device identity is a pseudonymous UUID plus an EC signing/agreement key in Android Keystore. HELLO packets carry the public key and an ECDSA signature. Peer sessions derive an AES key with ECDH; application payloads use AES-GCM. Message IDs provide replay/dedup protection.

## Emergency
SOS is priority 100, captures local GNSS when available, stores sender name/location/voice reference, relays with TTL, and triggers five local siren/vibration pulses.

## Voice
AAC/MP4 is used as the Android-native compressed fallback. Audio is split into bounded chunks, transferred as VOICE_META/VOICE_CHUNK packets and reassembled into a local file.
