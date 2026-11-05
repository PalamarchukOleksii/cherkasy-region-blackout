# Cherkasy Region Blackout

Material 3 Expressive Android home-screen widget (Jetpack Glance) for the hourly power outage schedule (ГПВ) in Cherkasy region.

- **Data:** the app scrapes cherkasyoblenergo.com on the phone; no server is needed.
- **Past outages kept:** it merges every "Оновлено…" version of the day, so outages that already happened stay on the bar.
- **Dates from titles:** each schedule's date comes from the post title, because the URLs are sometimes off by a day.
- **Colors** follow the wallpaper (dynamic color).
- **Tapping the widget** opens the app; the link to the original post is in the app.

```sh
./gradlew :app:installDebug        # needs Android SDK platform 37
./gradlew :core:test               # parser + timeline tests (saved site pages as fixtures)
```

## Web version

No Android, or don't want to install anything? The same schedule is on the web:
**https://palamarchukoleksii.github.io/cherkasy-region-blackout/**. Pick your queue and bookmark the page;
the queue is kept in the link (e.g. `#q=3.2`).

[.github/workflows/pages.yml](.github/workflows/pages.yml) rebuilds it every 15 minutes: `:web` fetches the
schedule with the same `:core` code as the app and publishes `schedule.json` next to the static page in
[web/site/](web/site/). One-time setup: repository **Settings → Pages → Source: GitHub Actions**.

```sh
./gradlew :web:site   # → web/build/site; preview with: python3 -m http.server -d web/build/site
```

## Install

Download the APK from the latest [GitHub Release](../../releases/latest), open it on the phone and allow installing
from that source. New versions install over the old one; your queue choice stays.

## Release

Every release must be signed with the same key, or phones refuse to update and users have to uninstall first.

**Locally** — signing reads `keystore.properties` in the project root (git-ignored; without it the APK is unsigned):

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

```sh
./gradlew :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

**On GitHub** — pushing a tag `vX.Y.Z` runs [.github/workflows/release.yml](.github/workflows/release.yml): tests,
signed build, and a GitHub Release with the APK attached. It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 upload.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `upload` |
| `KEY_PASSWORD` | the key password |

Before tagging, bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`: Android won't install an
update whose `versionCode` isn't higher. Back up the keystore and its passwords somewhere safe; if they're lost,
everyone has to uninstall and reinstall.

Privacy: [PRIVACY.md](PRIVACY.md).
