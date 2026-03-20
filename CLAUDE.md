# CLAUDE.md — tailscale_mobile

Standalone Android library/app demonstrating in-process Tailscale networking via `libtailscale`
(Go `c-shared`). No Tailscale app required on device.

## What this does

Streams camera video over RTMP to a peer on the Tailscale network without routing through the OS
network stack. Key trick: `TailscaleVpnService` opens a `ServerSocket` on `127.0.0.1:PORT` and
pipes each incoming connection to a `TsnetDial` file descriptor — bridging `GenericStream`'s raw
TCP into the Tailscale userspace WireGuard tunnel.

## Configuration

All three settings are entered in the app UI at runtime — no rebuild needed:

| Field | Default | Description |
|---|---|---|
| Auth key | *(empty)* | `tskey-auth-…` from Tailscale admin |
| Server Tailscale IP | *(empty)* | `100.x.y.z` of your RTMP server on the tailnet |
| Stream path suffix | `/live/stream` | RTMP path after the host, e.g. `/live/mystream` |

Values are stored in `TailscaleManager` at startup and read by `TailscaleVpnService`/`MainActivity`.
The server port is hardcoded to `1935` in `TailscaleManager.serverPort` — change it there if needed.

## Build

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> JDK: set `JAVA_HOME` in `~/.bashrc` before building. Do not override inline.

## Rebuild libtailscale .so (arm64)

Only needed if modifying `libtailscale/` Go source. Requires Go + Android NDK.

```bash
cd libtailscale

NDK=$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$NDK/aarch64-linux-android26-clang

GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=$CC \
  go build -buildmode=c-shared -tags android \
  -o libtailscale_android_arm64.so .

cp libtailscale_android_arm64.so ../android/app/src/main/jniLibs/arm64-v8a/libtailscale.so
cp libtailscale_android_arm64.h  ../android/app/src/main/cpp/
```

## Module map

| File | Purpose |
|---|---|
| `libtailscale/tailscale.go` | Go C API: `TsnetNewServer`, `TsnetLoopback`, `TsnetDial`, `TsnetGetIps`, `TsnetErrmsg`, `TsnetClose`, `TsnetSet*` |
| `libtailscale/android_init.go` | `TsnetSetAndroidDirs` (env vars for logpolicy) + fake `android0` interface so `netmon` reports `v4=true` |
| `android/.../TailscaleJni.kt` | Kotlin `external fun` declarations loading `libtailscale_jni.so` |
| `android/.../TailscaleVpnService.kt` | Tailscale lifecycle (connect → auth poll → proxy ServerSocket accept loop); extends `VpnService` for `NETLINK_ROUTE` permission |
| `android/.../TailscaleManager.kt` | Auth key + lazy `TAILSCALE_RTMP_URL` reading `proxyPort` |
| `android/.../MainActivity.kt` | Camera2 → `GenericStream` → `rtmp://127.0.0.1:proxyPort/...`; polls `TailscaleVpnService.isConnected` |
| `android/.../StreamService.kt` | Foreground service keeping WiFi/CPU alive when screen is off |
| `android/.../cpp/tailscale_jni.cpp` | JNI C++ bridge: `connect`, `getIp`, `dial`, `closeFd`, `close` |

## Known gotchas

- `InetAddress.getLoopbackAddress()` returns `::1` (IPv6) on some devices — always use `InetAddress.getByName("127.0.0.1")` for the `ServerSocket`.
- `TsnetLoopback` returns immediately before auth completes. Auth detection is done by polling `TsnetGetIps()` until a `100.x.y.z` address appears (~4s with cached state, up to 90s cold).
- The `VpnService` permission dialog appears once per install; subsequent launches skip it if already granted.
- Auth key is hardcoded — rotate in `TailscaleManager.kt` before expiry. Use ephemeral keys (set in `TsnetSetEphemeral`) so devices auto-expire from the tailnet.
- `tsnet_debug.log` written to `filesDir` — useful for diagnosing auth failures. Remove `TsnetSetLogFD` call in `tailscale_jni.cpp` for production.
