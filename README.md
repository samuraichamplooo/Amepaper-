# Amepaper 雨

A live wallpaper that combines **Rain Paper** (raindrops on glass, fog, weather sync) with **Paperize** (an interval changer with separate home and lock albums), plus a per-wallpaper **crop and aspect-ratio editor** for the cover and main screens of a foldable.

## Install

Each push to this repo builds an APK with GitHub Actions. Open **Releases**, tap the newest `Amepaper build N`, and download `Amepaper-N.apk`. Every build uses the same signing key, so new versions install over old ones and keep your albums and settings.

After installing, open Amepaper and tap **Set wallpaper**, then choose **Home and lock screens**.

## What's in it

**Wallpaper tab** (Paperize-style)
- Current lock and home wallpapers, a Lock/Home toggle for which screens rotate, and an album for each
- Wallpaper changing on/off, individual scheduling, a days/hours/minutes interval, shuffle, and "Change wallpaper now"
- Scaling (Fill / Fit / Stretch / None) and horizontal scrolling across home pages
- Visual effects per screen: brightness, blur, vignette, gray filter, and adaptive brightness in dark mode

**Rain tab** (Rain Paper-style)
- Live preview of the effect on your current home wallpaper
- Rain (sliding drops with trails), condensation droplets, drop size, fogged glass, refraction, trails, and speed
- Presets, a frame-rate setting, and a battery-saver mode
- Weather Sync from Open-Meteo: rain only when it's actually raining where you are (your location or a city you search for)

**Library tab**
- Albums. Add photos with the photo picker; they're copied into the app so they keep working if the originals move.
- Tap a wallpaper to crop it. The **Cover screen** and **Main screen** each get their own crop and aspect ratio (Screen, Original, 9:21, 9:19.5, 9:16, 3:4, 1:1, 4:3, 16:9…). Pinch to zoom, drag to position, double-tap to fit.
- Long-press a wallpaper to show it now on home or lock, or to remove it.

## How it works

- `RainWallpaperService` is an OpenGL ES 3 live wallpaper. When one engine covers both screens, it shows the lock image while the keyguard is up and cross-fades to the home image on unlock.
- `res/raw/rain_frag.glsl` is the rain shader: procedural sliding drops with stick-slip motion, trails that stay fixed to the glass and clear the fog, condensation droplets that grow and evaporate, and lens refraction (each drop shows a small upside-down view of what's behind it).
- The interval changer is checked whenever the wallpaper becomes visible, so a change that came due while the screen was off happens as soon as you look.
