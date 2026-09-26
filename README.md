# axon (Android)

**axon** is the AI-native fork of [ntfy](https://github.com/binwiederhier/ntfy). This is the
rebranded Android client: indigo theme, "axon" app name, and `axon.example.com` as the default
server. It speaks the unchanged ntfy protocol — pairs with any axon or ntfy server.

> Based on [binwiederhier/ntfy-android](https://github.com/binwiederhier/ntfy-android)
> (Apache-2.0). Wire protocol unchanged; upstream app compatibility maintained.

## Install

Grab `axon-fdroid-debug.apk` from the
[axon server releases](https://github.com/mptfire/axon/releases) (attached to each axon
release), or build it yourself:

```bash
./gradlew --no-daemon assembleFdroidDebug
# APK at app/build/outputs/apk/fdroid/debug/
```

The APK is debug-signed — Android will warn on install; allow install-from-unknown-sources
for your file manager/browser.

## Build flavors

- `fdroid` — no proprietary dependencies (recommended)
- `play` — includes FCM for instant push (requires google-services.json)

## Server

Point the app at your axon instance. The default server in this build is `axon.example.com`.
AI enrichment, translations, digests and scheduled briefings are applied server-side and
arrive as ordinary notifications — no app-side AI needed.
