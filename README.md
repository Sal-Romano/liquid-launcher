# Liquid Launcher

A home screen for a Fire TV (Hisense U6, Fire OS 8, Android 11) that is a photo frame first and a launcher second. Your photos fill the screen, the time floats in the middle, and one row of liquid-glass tiles fades away when you stop touching the remote.

The glass is real refraction, not a painted blur: a GLSL ES 3.0 fragment shader draws every panel as a slab with a bevelled edge, bends the photo behind it through `refract()` at IOR 1.52 with a little dispersion, softens the middle from the photo's own mipmap chain, and mirrors the photo around the panel on its rim. The light is the photo's own: each photo's brightest patch is found from its thumbnail when it is decoded (about a millisecond, nothing stored) and the glass hangs its lamp there, so a sunset lights the tiles from the sunset's side in its colour, every tile catches the glint at its own angle, and a focused tile pivots: it turns about 14 degrees away from the side focus came from and springs back flat, the icon clipped inside the rotated glass and the slab drawn in the same perspective, so the reflection sweeps across it like light over a turning pane. It runs in a `TextureView` between the wallpaper and the Compose chrome, because Android 11 has no `RenderEffect` and the TV's Mali-G52 cannot afford a per-frame blur.

Kotlin, Jetpack Compose for TV. The app is called Glass internally (package `dev.sal.glass`); the design spec is in [DESIGN.md](DESIGN.md).

## Status

Early. Everything below runs on an Android TV emulator; the first run on the TV itself is still ahead. Expect rough edges.

## What it does

- Wallpapers from `/sdcard/Pictures/Wallpapers` (push photos over adb): shuffle without repeats, 1.2 s crossfade, slow Ken Burns push-in, skip and pause from the remote.
- Clock block centred at 43 % of the height, updates on the minute, 12/24 h, three sizes.
- A dock of up to eight tiles driven by `launcher.json` (apps, intents, actions, folders, with Home Assistant entities reserved). Hold OK on a tile to move or hide it. A focused tile lifts, pivots and shows its name underneath.
- Folders: a tile that holds other tiles (the default config ships a Games folder for Wii, PS5, Xbox and Luna). It shows its children small, two by two, or one icon of its own (`icon`: a brand key or a package name) when it is really one app in several guises, and opens as its own row in the dock's place; Back returns to it.
- Rename an app in the All apps grid with `labels` in `launcher.json` (package to display name), for apps whose own label is wrong or ambiguous; it keeps the place its own name sorted it to.
- Stays awake: a photo frame is not inactivity, so the launcher holds the screen on while it is in front, always or for 1, 3 or 6 hours since the last key (Settings: Stay awake); Fire TV's own 21-minute timeout takes over after that, or from the start with "Fire TV decides".
- Its own volume pill: an accessibility service is told the instant Fire OS's volume box appears and draws the launcher's pill over it with the real level (speaker with waves, glowing track, the number, Muted), so the stock box is never seen (Settings: Volume pill). Fire OS never hands volume keys to apps, so this is the reach. Optionally the service also carries each press on to the next of 10, 20, 25 or 50 levels (Settings: Volume levels; off by default, since a soundbar on CEC can only be walked a step at a time and the Roku chirps on every step). An accessibility service watches the volume change, carries each press on to the next of 10, 20, 25 or 50 levels instead of Fire OS's 100, and draws its own pill over the stock popup (Settings: Volume levels; System leaves Fire OS alone). Fire OS never hands volume keys to apps, so this is the reach. With a Roku soundbar on eARC, `volume.soundbar` in `launcher.json` (its address) sends the steps over the network so a level jumps instead of ticking once per CEC round trip. Enabled once over adb, see `VolumeService`.
- Its own navigation sounds: a glass-bead tick when focus moves and a lower tap on OK, in place of the stock Fire OS click (Settings: Sounds Glass / System / Off). Synthesised, in `res/raw`.
- Top right: the network state (Wi-Fi bars, Ethernet, or struck through) and a glass settings button, reached with Up from the dock.
- Brand marks for Plex, Spotify, YouTube, PlayStation, Xbox and Wii, drawn like app icons so those tiles look right before the apps are installed, or in place of a stock icon that sits badly in the row (`icon` on an app, folder or action tile). A Claude mark for a tile that is only a picture (`action: None`).
- All apps grid, settings sheet, and a frosted-only glass mode for when the GPU disagrees.
- Drop to the TV: the launcher listens on port 8765 of the home network. Open `http://<tv-ip>:8765` on a laptop or phone and drag a PDF, an image or a link onto the page, or `curl -T file.pdf http://<tv-ip>:8765/`. PDFs and images open full screen on the TV (Left/Right page through a PDF, Back closes), links open in the TV's browser. The address is shown in Settings. No authentication: it trusts the LAN.
- Pre-rendered blurs cached on disk, about 30 KB per photo, rendered once in the background.

## Build and install

Needs JDK 17 and an Android SDK with platform 37 installed.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant dev.sal.glass android.permission.READ_EXTERNAL_STORAGE
adb shell appops set dev.sal.glass READ_EXTERNAL_STORAGE allow
adb push my-photo.jpg /sdcard/Pictures/Wallpapers/
```

Fire OS intercepts the Home button and will not let a third-party launcher be the default. [Home on Fire](https://github.com/toolicious/home-on-fire) (an accessibility-service redirect, GPLv3) can send Home presses to this app; the TV's own launcher still flashes for a moment unless it is patched to watch the activity log instead of the key.

For development without the TV, an Android TV API 31 image at 1920×1080 and density 320 stands in. Disable the stock launcher there (`adb shell pm disable-user --user 0 com.google.android.tvlauncher`) so Home lands here.

## Commits

Messages follow [Conventional Commits](https://www.conventionalcommits.org/): `feat`, `fix`, `perf`, `refactor`, `docs`, `build`, `chore`, with an optional scope such as `glass`, `dock`, `wallpaper` or `allapps`. Bug fixes are `fix:`.

## Credits

The refraction model follows two MIT-licensed WebGL references, [m2-md/liquid-glass-refraction-shader](https://github.com/m2-md/liquid-glass-refraction-shader) and [TiHxdvt/liquid-glass-webgl](https://github.com/TiHxdvt/liquid-glass-webgl); their notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The typeface is Inter, under the SIL Open Font License ([INTER-OFL.txt](INTER-OFL.txt)). Squircles come from `androidx.graphics.shapes`.

## License

No license has been chosen for this code yet.
