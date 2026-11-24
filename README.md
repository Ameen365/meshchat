# MeshChat – offline mesh messaging (Android)

Phones relay messages to each other over Bluetooth / Wi-Fi Direct (Google Nearby Connections),
with no internet, SIM or router.

## Run it
1. Install Android Studio (latest). File > Open > select this MeshChat folder. Let Gradle sync.
2. On each phone: Settings > About phone > tap Build number 7 times > Developer options > USB debugging ON.
3. Plug phone in by USB, select it at the top of Android Studio, press Run. Repeat for all 3 phones.
   (Or: Build > Build APK(s), copy app-debug.apk to the other phones and install.)
4. On every phone: turn Bluetooth ON and Location ON (needed on Android 12 and below). Wi-Fi ON also helps.
5. Open the app, name the phones "Phone A", "Phone B", "Phone C", allow all permissions.
6. Phones need Google Play Services (all normal Android phones have it).

## Demo script
1. Put the 3 phones together. Wait until each shows "Connected: 2".
2. Turn on airplane mode, then re-enable Bluetooth only. It still works.
3. Walk Phone A and Phone C apart until they're out of each other's range, keep Phone B in the middle.
4. Send a message from A. It appears on C with "via 2 hop(s): Phone A → Phone B → Phone C".
5. Press SOS to show the emergency mode.
6. Store-and-forward: send a message while C is far away. Walk C back, and it receives it on reconnect.

## How it works (for your viva)
- Nearby Connections, P2P_CLUSTER strategy = many-to-many mesh.
- Flood routing: unique message ID + TTL (6 hops) + duplicate drop + forward to all neighbours except the sender.
- Store-and-forward: the last 50 messages are re-sent to any phone that newly connects.
- Path tracking: each relay appends its name so you can see the route.

## Ideas to extend
End-to-end encryption (AES), private (direct) messages, battery-aware relaying, location in SOS, message ACKs.
