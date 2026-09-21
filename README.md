# DarkUI

DarkUI is an Android/Samsung utility that automatically creates a single **Dark**
appearance for every launchable app on the phone, builds a standard Android icon-pack
APK on-device, signs it, and opens Samsung Theme Park so the generated pack can be
applied to One UI Home.

There are no Dark/AMOLED/Tinted/manual per-app modes. The renderer makes the decision
automatically.

## Dark rendering strategy

The goal is to follow the same design principle Apple documents for generated icon
appearances: keep the icon recognizable, preserve its core visual features and
legibility, and generate a dark appearance when a dedicated dark asset is unavailable.

DarkUI adapts that principle to Android:

- **Adaptive icons:** background and foreground layers are processed separately.
  Colored backgrounds are moved to a dark version of the same hue while foreground
  symbols stay legible.
- **Flat legacy icons:** edge/background analysis identifies a simple background and
  converts only background-like pixels.
- **Transparent symbols:** the symbol is kept on a consistent dark One UI-style frame;
  very dark glyphs are lifted enough to remain visible.
- **Games and complex artwork:** the complete original artwork is preserved and only
  its luminosity is reduced. The renderer does not try to cut cars, characters,
  photos, or detailed scenes out of the icon.
- **Already-dark icons:** receive only a minimal treatment so they are not crushed.

The final result uses one consistent rounded One UI-style mask.

## Icon-pack pipeline

- Scans launchable apps without requesting `QUERY_ALL_PACKAGES`.
- Generates/caches Dark icons by package, version, and renderer version.
- Uses a precompiled icon-pack template containing **1024 distinct resource slots**.
- Rewrites only the PNG slot payloads and dynamic `assets/appfilter.xml` mapping.
- Preserves 4-byte alignment for uncompressed APK entries during the rewrite.
- Signs the generated APK with an RSA key stored in Android Keystore.
- Verifies the resulting APK signature before offering it for installation.
- Opens Samsung Theme Park for the final One UI application step.

Samsung does not expose a public API for silently applying third-party icon packs to
One UI Home, so Android installation confirmation and the Theme Park apply step remain
user-controlled.

## Build

The project is pinned to Android Gradle Plugin 8.13.2 / Gradle 8.13 / JDK 17 and
compileSdk 36.

```bash
gradle clean
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
gradle :app:lintDebug
```

## Automated checks

GitHub Actions performs all of the following:

1. builds the icon-pack template;
2. verifies that all 1024 PNG slots exist and are byte-distinct (preventing AAPT
   resource deduplication);
3. builds Debug and Release main APKs;
4. checks that the template APK is embedded in the main app;
5. runs renderer tests for adaptive/social-style icons, flat legacy icons,
   transparent glyphs, games/complex artwork, and already-dark icons;
6. runs Android lint.

## Samsung flow

1. Open DarkUI. Apps are scanned and Dark icons are generated automatically.
2. Review the before/after previews.
3. Tap **Gerar e instalar ícones Dark**.
4. The first time, allow "Install unknown apps" for DarkUI and confirm installation
   of **DarkUI Generated**.
5. Open Theme Park.
6. Icons → Create new → Iconpack → Third party icon packs → **DarkUI Generated**.
7. Save/apply the Theme Park icon theme.

When apps are installed or updated later, open DarkUI and tap **Atualizar** to rebuild
the pack.
