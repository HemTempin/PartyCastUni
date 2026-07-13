# PartyCast v5 architecture

## Core idea

PartyCast does not relay Bluetooth. The host phone is a normal Android audio player. Client phones send files, links and commands over local TCP; the host plays audio through Android's current audio output.

If a Bluetooth speaker is connected to the host, Android routes the host app's audio to that speaker.

## Split Network Mode

The main v5 change is separating local PartyCast traffic from Internet/WebView traffic.

```text
LAN traffic:
client → Wi‑Fi SocketFactory → host TCP server

Internet traffic:
host WebView / YouTube IFrame → Android default network
```

This means:

- local PartyCast sockets are created through `Network.getSocketFactory()` for the Wi‑Fi network;
- the whole process is not bound to Wi‑Fi;
- `ConnectivityManager.bindProcessToNetwork(null)` is used to clear process-level binding;
- WebView remains free to use Android's default network, which can be VPN.

This should allow:

```text
VPN remains enabled for YouTube
local room still tries to work over Wi‑Fi LAN
```

The user should avoid adding the whole app to VPN exclusions if YouTube requires VPN.

## Protocol

TCP port:

```text
45678
```

Protocol header:

```text
MAGIC: PCAS
VERSION: 3
ROOM_CODE: UTF
TYPE: int
```

Message types:

```text
1 TYPE_AUDIO_FILE
2 TYPE_LINK
3 TYPE_CONTROL
4 TYPE_PING
5 TYPE_PONG
```

## Discovery

Implemented:

1. NSD/mDNS via `_partycast._tcp`.
2. Local subnet scan using PING/PONG.
3. Hotspot host candidate search + subnet fallback.

## YouTube modes

v5 has two YouTube playback modes:

```text
embedded = WebView + YouTube IFrame Player API
external = ACTION_VIEW + MEDIA_PLAY fallback
```

Embedded mode can observe YouTube end events and continue the queue.

External mode is only a fallback. PartyCast cannot reliably know when external YouTube playback ends.

## Limitations

- If VPN lockdown blocks non-VPN traffic, Wi‑Fi SocketFactory may still fail.
- If the VPN app excludes PartyCast entirely, WebView cannot be forced back into VPN from app code.
- NSD/mDNS depends on router multicast support.
- Hotspot management cannot be fully automated by a normal Android app.
- Yandex/VK remain external best-effort playback.


## v5.1 changes

- Link input cleanup after successful `TYPE_LINK` send.
- External playback fallback now tries service packages first:
  - Yandex Music: `ru.yandex.music`
  - VK Music: `com.uma.musicvk`
  - VK main app: `com.vkontakte.android`
- External playback sends repeated `MEDIA_PLAY` key events because the target app may need time to become the active Android media session.
- Local/received files now report now-playing status and progress to `MainActivity` through `HostPlayer.Listener.onLocalNowPlayingChanged`.

Yandex/VK remain partial-control adapters. PartyCast owns the queue; external apps are only launch/play fallbacks.
