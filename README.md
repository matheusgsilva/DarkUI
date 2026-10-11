# DarkUI

DarkUI is an Android/Samsung utility that automatically creates a single **Dark**
appearance for every launchable app on the phone, builds a standard Android icon-pack
APK on-device, signs it, and opens Samsung Theme Park so the generated pack can be
applied to One UI Home.

There are no Dark/AMOLED/Tinted/manual per-app modes. The renderer makes the decision
automatically.

## Dark rendering strategy

The renderer follows the iOS 18/26/27 **Dark** app-icon appearance: keep the icon
recognizable, but rebuild it on a dark background and move the brand color into the
symbol.

- **One neutral dark plate:** every rebuilt background becomes the same graphite
  plate (`#222226` at the top fading to `#0C0C0E`), never a darkened copy of the
  brand color.
- **Brand color moves to the glyph:** a white symbol drawn directly on a vivid
  brand surface takes a light version of that brand color (a green chat bubble,
  a blue envelope, a red bank logo). Gradient surfaces pass their local color, so
  gradient brands get a gradient glyph. The tint is kept at a minimum luminance so
  it stays readable on the plate.
- **Colored and multicolor logos keep their colors**; only the background changes.
- **Dark glyphs on light bases are lifted** so they stay visible on the plate.
- **Enclosed details stay as authored:** white areas that do not touch the brand
  surface (mascot eyes, lens highlights) are not tinted.
- **Adaptive icons:** background and foreground layers are flattened through the
  launcher mask, and only colors change, never geometry.
- **Games and complex artwork:** the complete original artwork is preserved and only
  its luminosity is reduced.
- **Already-dark icons:** receive only a minimal treatment so they are not crushed.

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
5. runs renderer tests for the iOS-style plate and brand-to-glyph color transfer,
   adaptive/social-style icons, flat legacy icons, transparent glyphs,
   games/complex artwork, and already-dark icons;
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
