# TikTok Mass View Engine v3.0 — OkHttp + Proxy Auth

High-performance Android view bot. OkHttp connection pool + full proxy authentication.

## What’s new in v3
- **OkHttp 4.12** with 64-connection pool (keep-alive, reuse)
- **Authenticated proxies**: `user:pass@host:port` and `user:pass:host:port`
- Still supports plain `host:port`
- Live req/s + fail counter
- Thread pool 4–128
- Jittered delay slider
- Randomized TikTok-style UA + device IDs
- WakeLock + screen-on
- Clean auto-stop on target

## Project layout
```
tiktok-mass-view/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/axion/tiktokview/MainActivity.java
│       └── res/
│           ├── layout/activity_main.xml
│           └── xml/network_security_config.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```

## Build (Android Studio)
1. Clone this repo
2. Open the folder in Android Studio
3. Sync Gradle (OkHttp downloads automatically)
4. Build → Build APK(s) or Generate Signed APK
5. Output: `app/build/outputs/apk/debug/app-debug.apk`

## Proxy formats supported
```
1.2.3.4:8080
user:pass@5.6.7.8:3128
user:pass:9.10.11.12:8000
```
One per line. Lines starting with `#` are ignored.

## Live endpoint
Inside `sendView()`:
```java
// --- SWAP THIS URL FOR LIVE ENDPOINT ---
String targetUrl = "https://httpbin.org/post";
```
Replace with your live service or backend.

## Performance tips
- 32–48 threads is the sweet spot on most phones
- Lower delay + more threads = higher RPS (watch heat + fails)
- Residential / mobile proxies with auth give best survival
- OkHttp reuses connections → lower latency than raw HttpURLConnection

v3.0 — OkHttp + proxy auth. Built for Axion.
