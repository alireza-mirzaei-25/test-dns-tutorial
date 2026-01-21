# DNSTT Android Client

Android app for dnstt DNS tunnel client.

## Features

### DNS Management
- **Global DNS Library:** Access to 3,956+ public DNS servers
- **Custom DNS Lists:** Create and manage your own DNS server lists
- **DNS Import:** Import DNS servers from text files
- **Auto DNS:** Automatically find and connect to working DNS servers
- **Parallel Testing:** Test multiple DNS servers simultaneously (1-10 threads)
- **DNS Prioritization:** Previously successful DNS servers automatically prioritized
- **Real-time Testing:** Test DNS server latency with accurate measurements
- **One-Line Button Layout:** Clean, compact UI with all actions on a single row

### Connection Features
- **Smart Retry:** "It doesn't work" button to quickly switch to alternative DNS
- **Enhanced Logging:** Clear visual indicators showing which DNS is being used
- **Robust Cleanup:** Complete tunnel cleanup on disconnect and app close
- **Auto-Update Labels:** Configuration changes reflect immediately in UI

### Performance
- **Parallel DNS Testing:** 5x faster DNS discovery with configurable thread pool
- **Accurate Latency Reporting:** Fixed race conditions for precise measurements
- **Memory Optimized:** Proper cleanup prevents memory leaks and zombie connections

## Prerequisites

- Go 1.21+
- Android SDK (API 24+)
- gomobile: `go install golang.org/x/mobile/cmd/gomobile@latest`
- NDK (installed via Android Studio SDK Manager)

## Building

### Option 1: Build Script

```bash
./build-mobile.sh
```

This builds both the Go library and the Android app, producing `dnstt-client.apk`.

### Option 2: Manual Build

1. Build Go mobile library:

```bash
cd ..
gomobile bind -target=android -androidapi=24 -o android-app/app/libs/mobile.aar ./dnstt-client/mobile
```

2. Build Android app:

```bash
./gradlew assembleDebug
```

APK will be at `app/build/outputs/apk/debug/app-debug.apk`

## Installing

```bash
adb install dnstt-client.apk
```

## Usage

1. Enter your server's public key (from `server.pub`)
2. Enter the tunnel domain (e.g., `t.example.com`)
3. Select transport type:
   - **DoH**: DNS over HTTPS (recommended)
   - **DoT**: DNS over TLS
   - **UDP**: Plain UDP DNS
4. Enter resolver address:
   - DoH: `https://dns.google/dns-query`
   - DoT: `dns.google:853`
   - UDP: `1.1.1.1:53`
5. Set number of parallel tunnels (8-16 recommended)
6. Tap Connect

The app creates a SOCKS5 proxy on `127.0.0.1:1080`. Configure other apps to use this proxy.

## Configuration

Settings are persisted automatically. The app remembers:
- Transport type and address
- Domain
- Public key
- Number of tunnels

## Rebuilding Native Libraries (Optional)

The pre-built `libhev-socks5-tunnel.so` files are included in `app/src/main/jniLibs/`. If you need to rebuild them (e.g., to update or modify the tun2socks library):

1. Initialize the submodule:

```bash
git submodule update --init --recursive
```

2. Build with NDK:

```bash
cd app/src/main/jni
ndk-build
```

3. Copy the built `.so` files to `jniLibs/`:

```bash
cp -r ../libs/* ../jniLibs/
```

## Version Information

**Current Version:** 1.3.0 (Build 4)

### Recent Updates (v1.3.0)
- Improved DNS card button layout (all buttons on one line)
- Fixed DNS test latency display accuracy
- Enhanced connection logging with clear DNS IP indicators
- Auto-updating configuration labels
- Comprehensive cleanup on disconnect and app close

See [CHANGELOG.md](CHANGELOG.md) for complete version history.
