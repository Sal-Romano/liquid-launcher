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
    /** A bundled brand mark to draw instead of the app's icon: "plex", "playstation", "xbox", "wii", "spotify". */
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

@Serializable
enum class Action { AllApps, Settings, NextWallpaper, PauseSlideshow }

/** A launcher action rather than an app. */
@Serializable
@SerialName("action")
data class ActionTile(override val id: String, val action: Action, override val label: String? = null) : Tile

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

@Serializable
data class LauncherConfig(
    val schemaVersion: Int = 1,
    val rows: List<TileRow> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val wallpaper: WallpaperConfig = WallpaperConfig(),
    val clock: ClockConfig = ClockConfig(),
    val dock: DockConfig = DockConfig(),
    val glass: GlassConfig = GlassConfig(),
) {
    val dockTiles: List<Tile> get() = rows.firstOrNull()?.tiles ?: emptyList()
}
