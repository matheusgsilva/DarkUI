# DarkUI

DarkUI is an Android/Samsung utility that scans the launchable apps installed on the phone, generates iOS-style dark icon variants automatically, builds a real Android icon-pack APK on-device, signs/installs it, and opens Samsung Theme Park so the generated pack can be applied to One UI Home.

## What it does

- Detects launchable apps without `QUERY_ALL_PACKAGES` by declaring the launcher intent in `<queries>`.
- Understands `AdaptiveIconDrawable` layers and uses `monochrome` when available.
- Uses a heuristic foreground/background segmentation engine for legacy bitmap icons.
- Preserves brand colors for Dark/AMOLED modes and creates a monochrome/tinted mode.
- Caches generated icons by package/version/style/engine version.
- Creates a custom icon-pack APK from a precompiled template with 1024 resource slots.
- Replaces the slot PNGs and `assets/appfilter.xml` at runtime without needing `aapt2` on the phone.
- Signs the generated pack with a key stored in Android Keystore using Android `apksig`.
- Requests normal Android package-install confirmation and then opens Samsung Theme Park.
- Exports generated PNGs to `Pictures/DarkUI` for inspection or manual use.

## Important One UI behavior

Samsung Theme Park has no documented public API that lets a third-party app silently apply an icon pack. DarkUI automates generation and pack creation, but the user still confirms installation and selects/applies **DarkUI Generated** inside Theme Park. This is intentional and avoids brittle Accessibility automation.

## Build

Requirements:

- JDK 17+
- Android SDK 36
- Android Studio Meerkat (2024.3.1) or newer

```bash
gradle :app:assembleDebug
```

The main app build depends on `:iconpacktemplate:assembleRelease`; the unsigned template APK is copied into the main app assets automatically.

## Project modules

- `app`: scanner, smart icon engine, UI, pack builder, signer/installer, Theme Park handoff.
- `iconpacktemplate`: tiny icon-pack shell with 1024 generated drawable slots. The runtime builder swaps their PNG bytes and writes per-device mappings into `assets/appfilter.xml`.

## Samsung flow

1. Open DarkUI.
2. Tap **Scan apps**.
3. Choose **Dark**, **AMOLED**, or **Tinted**.
4. Review/regenerate any icon if desired.
5. Tap **Build icon pack**.
6. Allow "Install unknown apps" for DarkUI the first time and confirm installation/update of **DarkUI Generated**.
7. Tap **Open Theme Park**.
8. Theme Park → Icons → Create new → Iconpack → Third party icon packs → **DarkUI Generated** → Save/Apply.

## Notes

The runtime APK rewrite keeps the template resource table intact and only replaces predeclared PNG resource payloads. The generated pack supports up to 1024 distinct app icons per build. Multiple launcher activities from the same package map to the same generated icon.
