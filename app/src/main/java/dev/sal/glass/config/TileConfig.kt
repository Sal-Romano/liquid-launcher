package dev.sal.glass.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything on the home screen is one of these, in launcher.json. New kinds (a Home Assistant
 * toggle, a folder) are new subtypes; old configs keep loading because unknown keys are ignored.
 */
@Serializable
sealed interface Tile {
    val id: String
    val label: String?
}

/** Launch an installed app. [component] is "package/activity". */
@Serializable
@SerialName("app")
data class AppTile(
    override val id: String,
    val component: String,
    override val label: String? = null,
    /** Hide the tile when this package is missing; false shows it dimmed with an install hint. */
    val requirePackage: Boolean = true,
    /** A bundled brand mark to draw instead of the app's icon: "plex", "playstation", "xbox", "wii", "spotify", "youtube", "claude". */
    val icon: String? = null,
) : Tile {
    val packageName: String get() = component.substringBefore('/')
}

/** Fire any intent URI (Intent.toUri(URI_INTENT_SCHEME)). */
@Serializable
@SerialName("intent")
data class IntentTile(
    override val id: String,
    val intentUri: String,
    override val label: String? = null,
    val requirePackage: String? = null,
) : Tile

/**
 * Ask another machine on the network to do something: a GET to [url], which answers when it is done.
 * The Wii tile is one: the Mac mini opens Dolphin, waits for it, and switches the TV to its input.
 */
@Serializable
@SerialName("hook")
data class HookTile(
    override val id: String,
    val url: String,
    override val label: String? = null,
    /** A bundled brand mark to draw. */
    val icon: String? = null,
) : Tile

@Serializable
/**
 * None: a tile that is only a picture (a logo in the dock); OK does nothing but tap.
 * Wallpapers: the photos on the TV and the QR code for sending one from a phone.
 */
enum class Action { AllApps, Settings, NextWallpaper, PauseSlideshow, None, Wallpapers }

/** A launcher action rather than an app. */
@Serializable
@SerialName("action")
data class ActionTile(
    override val id: String,
    val action: Action,
    override val label: String? = null,
    /** A bundled brand mark to draw instead of the action's glyph. */
    val icon: String? = null,
) : Tile

/** v2: a Home Assistant entity. Parsed and shown as a placeholder today. */
@Serializable
@SerialName("ha")
data class HaEntityTile(
    override val id: String,
    val entityId: String,
    val kind: String = "Toggle",
    override val label: String? = null,
) : Tile

/** v2: a folder of tiles. Parsed and shown as a placeholder today. */
@Serializable
@SerialName("folder")
data class FolderTile(
    override val id: String,
    override val label: String? = null,
    val children: List<Tile> = emptyList(),
    /**
     * Draw this instead of the two-by-two of children: a bundled brand key ("spotify") or a package
     * name whose installed icon to borrow. For a folder that is really one app in several guises.
     */
    val icon: String? = null,
) : Tile

@Serializable
enum class RowVisibility { Always, OnFocus, Hidden }

@Serializable
data class TileRow(
    val id: String,
    val title: String? = null,
    val visibility: RowVisibility = RowVisibility.Always,
    val tiles: List<Tile> = emptyList(),
)

@Serializable
enum class WallpaperOrder { Shuffle, ByName }

@Serializable
data class WallpaperConfig(val intervalSec: Int = 60, val kenBurns: Boolean = true, val order: WallpaperOrder = WallpaperOrder.Shuffle)

@Serializable
enum class ClockSize { S, M, L }

@Serializable
enum class HourFormat { System, H12, H24 }

@Serializable
data class ClockConfig(val size: ClockSize = ClockSize.M, val hourFormat: HourFormat = HourFormat.System)

@Serializable
enum class GlassMode { Refraction, Frosted }

@Serializable
data class GlassConfig(val mode: GlassMode = GlassMode.Refraction)

@Serializable
data class DockConfig(
    /** 0 = never hide. */
    val idleHideSec: Int = 10,
)

/** Glass: the launcher's own tick and tap. System: the stock Fire OS click. Off: nothing. */
@Serializable
enum class SoundMode { Glass, System, Off }

@Serializable
data class SoundConfig(val mode: SoundMode = SoundMode.Glass)

/**
 * How long the launcher keeps the TV awake while it is in front, in minutes: a photo frame should
 * not count as inactivity. 0 = always; -1 = Fire TV decides (its own timeout, 21 min on the Hisense).
 * After the time is up without a key press the flag drops and Fire TV's timeout takes over.
 */
@Serializable
data class AwakeConfig(val minutes: Int = 0)

/**
 * [pill]: draw the launcher's volume pill over Fire OS's box. [levels]: how many steps the volume
 * keys move through instead of the TV's 100 (0 = Fire TV's own steps; the default, since a soundbar
 * on CEC can only be walked a step at a time).
 */
@Serializable
data class VolumeConfig(
    val pill: Boolean = true,
    val levels: Int = 0,
    /**
     * A Roku soundbar's address, when the TV's audio goes to one over eARC. The TV can only tap the
     * soundbar's volume key once per CEC round trip (about a second each), so a level of five steps
     * ticked five times; over the network (Roku ECP, port 8060) the taps land in a fraction of a
     * second and the level jumps once. Null: CEC taps only.
     */
    val soundbar: String? = null,
)

/**
 * Photos from a phone: the launcher keeps a socket open to a relay (cloud/, a Cloudflare Worker)
 * and the Wallpapers screen shows a QR code for it. [server] null: the build's own relay
 * (BuildConfig.PHONE_SERVER); "" turns the phone link off; anything else is another relay's
 * address, such as http://10.0.2.2:8787 for `wrangler dev` from the emulator.
 */
@Serializable
data class PhoneConfig(val server: String? = null)

@Serializable
data class LauncherConfig(
    val schemaVersion: Int = 1,
    val rows: List<TileRow> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val clock: ClockConfig = ClockConfig(),
    val dock: DockConfig = DockConfig(),
    val glass: GlassConfig = GlassConfig(),
    val sound: SoundConfig = SoundConfig(),
    val volume: VolumeConfig = VolumeConfig(),
    val awake: AwakeConfig = AwakeConfig(),
    val phone: PhoneConfig = PhoneConfig(),
    /** Display names by package for the All apps grid, for apps whose own label is wrong or ambiguous. A renamed app keeps the place its own name sorted it to. */
    val labels: Map<String, String> = emptyMap(),
) {
    val dockTiles: List<Tile> get() = rows.firstOrNull()?.tiles ?: emptyList()
}
