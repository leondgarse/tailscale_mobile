# CLAUDE.md — tailscale_mobile

Reusable Android library for in-process Tailscale networking. No Tailscale app required on device.
Published to JitPack as `com.github.leondgarse:tailscale_mobile`.

## What this does

Provides a local TCP proxy (`127.0.0.1:PORT`) that tunnels any TCP connection to a peer on the
Tailscale network using userspace WireGuard. Any TCP client (RTMP, HTTP, custom protocols) connects
to `127.0.0.1:proxyPort` instead of the peer IP directly.

Key trick: `TailscaleProxyService` opens a `ServerSocket` on `127.0.0.1` and pipes each incoming
connection to a `TsnetDial` file descriptor — bridging Android's TCP socket APIs into the Tailscale
userspace WireGuard tunnel. `VpnService` is held (without creating a TUN device) purely to gain
`NETLINK_ROUTE` permission, which Android SELinux blocks for untrusted apps.

## Repository structure

```
libtailscale/       Go source → c-shared .so (fork of tailscale/libtailscale + Android additions)
android/
  lib/              Android library module (AAR) — com.tailscale.mobile
  sample/           Sample app — RTMP streaming over Tailscale
settings.gradle.kts  Root Gradle entry point (required for JitPack discovery)
jitpack.yml         JitPack build config — downloads prebuilt .so from GitHub Release assets
```

## Module map

| File | Purpose |
|---|---|
| `libtailscale/tailscale.go` | Go C API: `TsnetNewServer`, `TsnetDial`, `TsnetListen`, `TsnetGetIps`, `TsnetErrmsg`, `TsnetClose`, `TsnetSet*` |
| `libtailscale/android_init.go` | `TsnetSetAndroidDirs` (env vars for logpolicy) + fake `android0` interface so `netmon` reports `v4=true` |
| `android/lib/.../TailscaleConfig.kt` | `Serializable` data class: `authKey`, `peerIp`, `peerPort`, `hostname` |
| `android/lib/.../TailscaleJni.kt` | Kotlin `external fun` declarations loading `libtailscale_jni.so` |
| `android/lib/.../TailscaleProxyService.kt` | Tailscale lifecycle + local TCP proxy `ServerSocket` accept loop; extends `VpnService` |
| `android/sample/.../MainActivity.kt` | RTMP sample: config UI (auth key, peer IP, RTMP path) → permissions → VPN → `TailscaleProxyService` → `GenericStream` pushes `rtmp://127.0.0.1:PORT/path` |
| `android/sample/.../StreamService.kt` | Foreground service keeping WiFi/CPU alive when screen is off |
| `android/lib/src/main/cpp/tailscale_jni.cpp` | JNI C++ bridge: `connect`, `getIp`, `dial`, `closeFd`, `close` |

## Build

```bash
# Build sample APK (run from repo root)
./gradlew :sample:assembleDebug
adb install -r android/sample/build/outputs/apk/debug/sample-debug.apk
```

> JDK: set `JAVA_HOME` in `~/.bashrc` before building. Do not override inline.

## Rebuild libtailscale .so (all ABIs)

Only needed if modifying `libtailscale/` Go source. Go is at `/opt/go/bin/go`.

```bash
NDK=~/Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin
cd libtailscale

GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=$NDK/aarch64-linux-android26-clang \
  /opt/go/bin/go build -buildmode=c-shared -tags android -o libtailscale_arm64.so .

GOOS=android GOARCH=arm GOARM=7 CGO_ENABLED=1 CC=$NDK/armv7a-linux-androideabi24-clang \
  /opt/go/bin/go build -buildmode=c-shared -tags android -o libtailscale_armv7a.so .

GOOS=android GOARCH=amd64 CGO_ENABLED=1 CC=$NDK/x86_64-linux-android26-clang \
  /opt/go/bin/go build -buildmode=c-shared -tags android -o libtailscale_x86_64.so .

cp libtailscale_arm64.so  ../android/lib/src/main/jniLibs/arm64-v8a/libtailscale.so
cp libtailscale_armv7a.so ../android/lib/src/main/jniLibs/armeabi-v7a/libtailscale.so
cp libtailscale_x86_64.so ../android/lib/src/main/jniLibs/x86_64/libtailscale.so
cp libtailscale_arm64.h   ../android/lib/src/main/cpp/libtailscale.h
```

## Releasing

1. Commit and push changes
2. Create a GitHub Release with all three `.so` files attached:
   ```bash
   git tag v1.x.y && git push origin v1.x.y
   gh release create v1.x.y \
     libtailscale/libtailscale_arm64.so \
     libtailscale/libtailscale_armv7a.so \
     libtailscale/libtailscale_x86_64.so \
     --title "v1.x.y" --repo leondgarse/tailscale_mobile
   ```
3. Trigger JitPack at https://jitpack.io/#leondgarse/tailscale_mobile

## Known gotchas

- `InetAddress.getLoopbackAddress()` returns `::1` (IPv6) on some devices — always use `InetAddress.getByName("127.0.0.1")` for the `ServerSocket`.
- `TsnetLoopback` returns before auth completes. Auth is detected by polling `TsnetGetIps()` until a `100.x.y.z` address appears (~4s cached, up to 90s cold start).
- The `VpnService` permission dialog appears once per install; subsequent launches skip it if already granted.
- Use ephemeral auth keys (`TsnetSetEphemeral`) so devices auto-expire from the tailnet.
- Tailscale logs are discarded in production (`TsnetSetLogFD(ts_handle, -1)`). To debug auth failures, temporarily change `-1` to a real fd: `open("<filesDir>/tsnet_debug.log", O_WRONLY|O_CREAT|O_APPEND, 0644)`.
- The `.so` files are not stored in git — they are downloaded from GitHub Release assets by `jitpack.yml` at build time.
- **Qualcomm HW AAC encoder stall**: on Snapdragon devices `c2.qti.aac.hw.encoder` takes ~60s to produce its first output frame. gortmplib's `readTracks()` window is 2s — MediaMTX sees only 1 track (H264) and eventually disconnects. Fix: `stream.forceCodecType(CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND, CodecUtil.CodecType.SOFTWARE)` before `prepareAudio()`.
- **MediaMTX 1.16.x RTMP setup**: (1) pin to `bluenviron/mediamtx:1.16.3-ffmpeg` — v1.17.0 breaks RootEncoder 2.6.7; (2) set `readTimeout: 30s` — default 10s fires during WireGuard handshake on first connect; (3) declare paths explicitly (`paths: live/stream:`) — undeclared paths are rejected immediately.
- **Proxy buffer size determines stream latency**: the `ByteArray` buffer in `handleClient()` directly controls lag. At 8Mbps a 64KB buffer takes ~260ms per read/write cycle — accumulated this becomes 15-20s end-to-end. At 2Mbps with 4KB buffers each cycle is ~16ms → near real-time. Rule of thumb: keep `bufferBytes / bitrateBytesPerSec < 20ms`. Confirmed: 1280×720@15fps@2Mbps with 4KB buffers → ~1s lag; 1920×1080@15fps@2Mbps → ~4s; 1920×1080@30fps@8Mbps → ~20s.
