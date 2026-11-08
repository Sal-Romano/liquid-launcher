package dev.sal.glass.config

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sal.glass.data.Apps
import kotlinx.serialization.json.Json
import java.io.File

/**
 * launcher.json in the app's files dir. Sal can also edit it over adb:
 *   adb shell run-as dev.sal.glass cat files/launcher.json   (debug build)
 * Writes go through a temp file + rename so a crash mid-write never leaves a half config.
 */
class ConfigStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = File(context.filesDir, "launcher.json")

    var config by mutableStateOf(load()); private set

    private fun load(): LauncherConfig {
        if (file.exists()) {
            runCatching { return json.decodeFromString<LauncherConfig>(file.readText()) }
                .onFailure { Log.w("Glass", "launcher.json unreadable, regenerating", it) }
        }
        return defaults().also { persist(it) }
    }

    /** Plex first even if absent, then the big streaming apps (Spotify even if absent), then All apps. */
    private fun defaults(): LauncherConfig {
        val installed = Apps.launchable(context).associateBy { it.packageName }
        val tiles = mutableListOf<Tile>()
        tiles += AppTile("plex", "com.plexapp.android/com.plexapp.plex.activities.SplashActivity", "Plex", requirePackage = false)
        for (p in PREFERRED) {
            val app = installed[p.pkg]
            tiles += when {
                app != null -> AppTile(p.pkg, "${app.packageName}/${app.activityName}", p.label, icon = p.icon)
                // Not installed yet but wanted anyway: dimmed with an install hint, like Plex.
                p.component != null -> AppTile(p.pkg, p.component, p.label, requirePackage = false, icon = p.icon)
                else -> continue
            }
        }
        // Nothing preferred is installed (a fresh device, the emulator): seat the first few real
        // apps so the dock is a dock and not a few lonely tiles.
        if (PREFERRED.none { it.pkg in installed }) {
            installed.values.filter { it.packageName !in Apps.HIDDEN_PACKAGES }.take(5).forEach { app ->
                tiles += AppTile(app.packageName, "${app.packageName}/${app.activityName}", app.label)
            }
        }
        // A logo that does nothing: OK only taps.
        tiles += ActionTile("claude", Action.None, "Claude", icon = "claude")
        // Games: a folder that opens as its own row. Wii through Dolphin, PS5 through PS Remote
        // Play, Xbox through the Xbox app, Luna; all drawn with their marks even before they are installed.
        tiles += FolderTile("games", "Games", listOf(
            AppTile("wii", "org.dolphinemu.dolphinemu/org.dolphinemu.dolphinemu.ui.main.TvMainActivity", "Wii", requirePackage = false, icon = "wii"),
            AppTile("ps5", "com.playstation.remoteplay/com.playstation.remoteplay.MainActivity", "PS5", requirePackage = false, icon = "playstation"),
            AppTile("xbox", "com.microsoft.xboxone.smartglass/com.microsoft.xboxone.smartglass.MainActivity", "Xbox", requirePackage = false, icon = "xbox"),
            AppTile("luna", "com.amazon.spiderpork/com.amazon.spiderpork.activities.MainActivity", "Luna"),
        ))
        tiles += ActionTile("all-apps", Action.AllApps, "All apps")
        return LauncherConfig(rows = listOf(TileRow("dock", tiles = tiles)), hidden = Apps.HIDDEN_PACKAGES)
    }

    fun update(transform: (LauncherConfig) -> LauncherConfig) {
        config = transform(config)
        persist(config)
    }

    fun move(tileId: String, delta: Int) = update { c ->
        val tiles = c.dockTiles.toMutableList()
        val i = tiles.indexOfFirst { it.id == tileId }
        val j = (i + delta).coerceIn(0, tiles.lastIndex)
        if (i < 0 || i == j) return@update c
        tiles.add(j, tiles.removeAt(i))
        c.withDock(tiles)
    }

    fun moveToFront(tileId: String) = update { c ->
        val tiles = c.dockTiles.toMutableList()
        val i = tiles.indexOfFirst { it.id == tileId }
        if (i <= 0) return@update c
        tiles.add(0, tiles.removeAt(i))
        c.withDock(tiles)
    }

    fun hide(tileId: String) = update { c -> c.withDock(c.dockTiles.filterNot { it.id == tileId }) }

    private fun LauncherConfig.withDock(tiles: List<Tile>): LauncherConfig {
        val rows = rows.toMutableList()
        if (rows.isEmpty()) rows += TileRow("dock", tiles = tiles) else rows[0] = rows[0].copy(tiles = tiles)
        return copy(rows = rows)
    }

    private fun persist(c: LauncherConfig) {
        runCatching {
            val tmp = File(file.parentFile, "launcher.json.tmp")
            tmp.writeText(json.encodeToString(LauncherConfig.serializer(), c))
            if (!tmp.renameTo(file)) error("rename failed")
        }.onFailure { Log.e("Glass", "could not save launcher.json", it) }
    }

    companion object {
        /** Dock order after Plex. A [component] keeps the tile even when the app is missing; an [icon] is a brand mark drawn instead of the app's own. */
        private class Preferred(val pkg: String, val label: String, val component: String? = null, val icon: String? = null)

        private val PREFERRED = listOf(
            Preferred("com.amazon.firetv.youtube", "YouTube", icon = "youtube"),
            Preferred("com.spotify.tv.android", "Spotify", component = "com.spotify.tv.android/com.spotify.tv.android.SpotifyTVActivity", icon = "spotify"),
            Preferred("com.apple.atve.amazon.appletv", "Apple TV"),
        )
    }
}
