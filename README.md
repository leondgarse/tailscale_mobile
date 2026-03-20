# tailscale-mobile

Embed Tailscale in-process on Android — no Tailscale app required on device.

The library provides a local TCP proxy (`127.0.0.1:PORT`) that tunnels any TCP connection to a peer on your tailnet using userspace WireGuard. Any Android TCP client (RTMP, HTTP, custom protocols, databases) can reach private Tailscale peers by connecting to the local proxy address instead of the peer IP directly.

This repository has two independent layers. You can use either one alone or together.

---

## Layer 1: `libtailscale` — C shared library

A fork of [`tailscale/libtailscale`](https://github.com/tailscale/libtailscale) compiled as a Go `c-shared` library. Exposes Tailscale's [`tsnet`](https://pkg.go.dev/tailscale.com/tsnet) package via a C API.

### Limitations

- **Not an importable Go module.** The `go.mod` here exists only to pin build dependencies for producing the `.so`. If you are writing a Go program, import [`tailscale.com/tsnet`](https://pkg.go.dev/tailscale.com/tsnet) directly instead — it provides the same functionality as a proper Go API:
  ```go
  import "tailscale.com/tsnet"

  s := &tsnet.Server{Hostname: "myapp", AuthKey: "tskey-auth-…"}
  defer s.Close()
  conn, err := s.Dial(context.Background(), "tcp", "100.x.y.z:1935")
  // conn is a net.Conn — use it directly
  ```
- **arm64 only** (prebuilt). Rebuild from source for other ABIs.
- **TCP only** in the Android proxy layer. The C API itself supports UDP via `tailscale_dial`/`tailscale_listen`, but the Android `TailscaleProxyService` only proxies TCP.

### Android-specific additions over upstream

- **`android_init.go`** — registers a synthetic `android0` interface so `netmon` reports IPv4 connectivity. Android SELinux blocks `/proc/net/*` for untrusted apps, which causes Tailscale's control client to pause indefinitely. This fake interface unblocks it without a real TUN device.
- **`TsnetSetAndroidDirs(dir)`** — sets `HOME`, `TMPDIR`, and `TS_LOGS_DIR` to an app-writable path before any Tailscale call. Required because Go's log policy looks for these at startup.

### C API reference (`tailscale.h`)

| Function | Description |
|---|---|
| `tailscale_new()` | Allocate a Tailscale server handle |
| `tailscale_set_authkey(sd, key)` | Set auth key |
| `tailscale_set_hostname(sd, name)` | Set node hostname on the tailnet |
| `tailscale_set_dir(sd, path)` | Set writable state directory |
| `tailscale_set_logfd(sd, fd)` | Redirect logs to a file descriptor (`-1` to discard) |
| `tailscale_up(sd)` | Start and wait until connected to the tailnet |
| `tailscale_dial(sd, "tcp", "100.x.y.z:port", &conn)` | Open a TCP connection to a peer; returns a plain `fd` |
| `tailscale_listen(sd, "tcp", ":port", &listener)` | Listen for incoming tailnet connections |
| `tailscale_accept(listener, &conn)` | Accept a connection; returns a plain `fd` |
| `tailscale_loopback(sd, ...)` | Start a SOCKS5 + LocalAPI loopback server |
| `tailscale_close(sd)` | Shut down the Tailscale server |

All connections are plain Unix file descriptors — use `read(2)`, `write(2)`, `close(2)` directly.

### Building for Android (arm64)

Only needed if modifying Go source. Requires Go 1.21+ and Android NDK r25+.

```bash
cd libtailscale

NDK=$ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin
CC=$NDK/aarch64-linux-android26-clang

GOOS=android GOARCH=arm64 CGO_ENABLED=1 CC=$CC \
  go build -buildmode=c-shared -tags android \
  -o libtailscale_android_arm64.so .

cp libtailscale_android_arm64.so ../android/lib/src/main/jniLibs/arm64-v8a/libtailscale.so
cp libtailscale_android_arm64.h  ../android/lib/src/main/cpp/
```

A prebuilt `.so` (Tailscale v1.94.1, arm64) is included at `android/lib/src/main/jniLibs/arm64-v8a/libtailscale.so`.

---

## Layer 2: `android/` — Android library + sample app

### Why a local TCP proxy?

`tailscale_dial` returns a raw file descriptor. Android socket APIs open their own OS-level TCP sockets and cannot be redirected through a file descriptor or SOCKS5 proxy transparently. The library bridges the gap with a `ServerSocket` on `127.0.0.1` that accepts normal connections and pipes each one to a `tailscale_dial` fd:

```
Your client (any TCP protocol)
       │ connect to 127.0.0.1:proxyPort
       ▼
TailscaleProxyService  ←  ServerSocket(127.0.0.1)
       │ tailscale_dial("tcp", "100.x.y.z:peerPort") → fd
       │ bidirectional pipe (two threads per connection)
       ▼
100.x.y.z:peerPort  (peer on the tailnet, userspace WireGuard)
```

**Why `VpnService`**: Android SELinux blocks `NETLINK_ROUTE` for untrusted apps, preventing `netmon` from seeing network interfaces → `v4=false` → control client paused → auth never completes. Holding a `VpnService` grants the required permission. No TUN device is created or used.

### Module structure

```
android/
  lib/                      ← Android library module (AAR, published to JitPack)
    src/main/
      cpp/
        tailscale_jni.cpp   JNI bridge → libtailscale C API
        CMakeLists.txt
      jniLibs/arm64-v8a/
        libtailscale.so     Prebuilt Go c-shared (arm64, Tailscale v1.94.1)
      java/com/tailscale/mobile/
        TailscaleConfig.kt       Configuration data class
        TailscaleJni.kt          Kotlin JNI declarations
        TailscaleProxyService.kt Tailscale lifecycle + local TCP proxy

  sample/                   ← Sample app — RTMP streaming over Tailscale
    src/main/java/com/tailscale/mobile/sample/
      MainActivity.kt       Config UI → TailscaleProxyService → GenericStream (RTMP)
      StreamService.kt      Foreground service (screen-off keepalive)
```

### Using the library (JitPack)

Add to your root `build.gradle`:
```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the dependency:
```groovy
implementation 'com.github.leondgarse:tailscale_mobile:1.0.0'
```

### 1. Request VPN permission and start

```kotlin
// In your Activity — request VPN permission once (shows system dialog first time):
val vpnIntent = VpnService.prepare(this)
if (vpnIntent != null) startActivityForResult(vpnIntent, VPN_REQUEST)
else launchTailscale()

fun launchTailscale() {
    TailscaleProxyService.start(this, TailscaleConfig(
        authKey  = "tskey-auth-…",   // from https://login.tailscale.com/admin/settings/keys
        peerIp   = "100.x.y.z",      // Tailscale IP of your target peer
        peerPort = 1935,              // TCP port on the peer
    ))
}
```

### 2. Wait for ready, then connect

```kotlin
// Poll until the proxy is listening (typically 4–8s with cached auth state):
Handler(Looper.getMainLooper()).post(object : Runnable {
    override fun run() {
        if (TailscaleProxyService.isReady) {
            val port = TailscaleProxyService.proxyPort
            // Connect any TCP client to 127.0.0.1:port
            myClient.connect("127.0.0.1", port)
        } else {
            Handler(Looper.getMainLooper()).postDelayed(this, 500)
        }
    }
})
```

### 3. Stop

```kotlin
TailscaleProxyService.stop(this)
```

### Build the sample APK

```bash
cd android
./gradlew :sample:assembleDebug
adb install -r sample/build/outputs/apk/debug/sample-debug.apk
```

---

## Publishing a release

The prebuilt `libtailscale.so` (31 MB) is **not stored in git**. It is attached as a GitHub Release asset and downloaded by JitPack at build time via `jitpack.yml`.

### First time: create the GitHub repository

The GitHub repository is already created at `github.com/leondgarse/tailscale_mobile`.

From this directory:
```bash
git init
git add .
git commit -m "Initial release v1.0.0"
git remote add origin https://github.com/leondgarse/tailscale_mobile.git
git push -u origin main
```

### Create a GitHub Release with the `.so` attached

Using the GitHub CLI:
```bash
gh release create v1.0.0 \
  android/lib/src/main/jniLibs/arm64-v8a/libtailscale.so \
  --title "v1.0.0" \
  --notes "Initial release. Tailscale v1.94.1, arm64." \
  --repo leondgarse/tailscale_mobile
```

Or via the GitHub web UI: go to **Releases → Draft a new release**, tag `v1.0.0`, and upload `libtailscale.so`.

The release tag (`v1.0.0`) must match what JitPack passes as `${VERSION}` in `jitpack.yml`.

### Trigger JitPack

Open `https://jitpack.io/#leondgarse/tailscale_mobile` — click **Get it** next to `v1.0.0`. JitPack will download the `.so` from the release asset and build the AAR.

If the build fails, check the log at `https://jitpack.io/#leondgarse/tailscale_mobile/v1.0.0`.

### For subsequent releases

1. Build the new `.so` (see [Building for Android](#building-for-android-arm64) above)
2. Update `version` in `android/lib/build.gradle.kts`
3. Create a new GitHub Release with the updated `.so`:
   ```bash
   gh release create v1.1.0 \
     /path/to/new/libtailscale.so \
     --title "v1.1.0"
   ```
4. Trigger JitPack for the new tag

### Verify

After JitPack builds successfully, add to a test project and sync:
```groovy
implementation 'com.github.leondgarse:tailscale_mobile:1.0.0'
```

---

## License

BSD-3-Clause (same as upstream [tailscale/libtailscale](https://github.com/tailscale/libtailscale)).
