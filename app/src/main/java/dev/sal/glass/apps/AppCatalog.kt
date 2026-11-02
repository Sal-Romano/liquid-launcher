package dev.sal.glass.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One installed app as the launcher sees it. [icon] is the 112 px squircle raster; null means monogram. */
data class AppInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** Has a LEANBACK_LAUNCHER activity. */
    val isTv: Boolean,
    val icon: ImageBitmap?,
) {
    val component: String get() = "$packageName/$activityName"

    val launchIntent: Intent
        get() = Intent(Intent.ACTION_MAIN)
            .addCategory(if (isTv) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(packageName, activityName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * PackageManager snapshot of every launchable app plus a per-package icon raster cache.
 * Refreshed by [PackageChangeReceiver] and on every Home press.
 */
class AppCatalog(private val context: Context, private val scope: CoroutineScope) {
    var apps by mutableStateOf<List<AppInfo>>(emptyList())
        private set

    private val iconCache = HashMap<String, ImageBitmap?>()

    fun refresh(): Job = scope.launch {
        val list = withContext(Dispatchers.IO) { query(withIcons = true) }
        apps = list
        Log.i(TAG, "catalog: ${list.size} apps (${list.count { it.isTv }} tv)")
    }

    /** Synchronous, icon-less listing for building the first-run defaults. */
    fun queryNow(): List<AppInfo> = query(withIcons = false)

    fun find(packageName: String, className: String? = null): AppInfo? =
        apps.firstOrNull { it.packageName == packageName && (className.isNullOrEmpty() || it.activityName == className) }
            ?: apps.firstOrNull { it.packageName == packageName }

    fun isInstalled(packageName: String): Boolean =
        try { context.packageManager.getPackageInfo(packageName, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    private fun query(withIcons: Boolean): List<AppInfo> {
        val pm = context.packageManager
        fun resolve(category: String): List<ResolveInfo> =
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
        val byPackage = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        // Some packages list several Leanback activities; keep the first per package.
        for (ri in resolve(Intent.CATEGORY_LEANBACK_LAUNCHER)) byPackage.putIfAbsent(ri.activityInfo.packageName, ri to true)
        for (ri in resolve(Intent.CATEGORY_LAUNCHER)) byPackage.putIfAbsent(ri.activityInfo.packageName, ri to false)
        byPackage.remove(context.packageName)
        return byPackage.values.map { (ri, tv) ->
            val ai = ri.activityInfo
            AppInfo(
                packageName = ai.packageName,
                activityName = ai.name,
                label = ri.loadLabel(pm).toString(),
                isTv = tv,
                icon = if (withIcons) iconFor(ai.packageName, ai.applicationInfo, ri) else null,
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun iconFor(pkg: String, ai: ApplicationInfo, ri: ResolveInfo): ImageBitmap? = synchronized(iconCache) {
        iconCache.getOrPut(pkg) {
            runCatching { IconRaster.render(context, ai, ri) }
                .onFailure { Log.w(TAG, "icon $pkg: $it") }
                .getOrNull()
        }
    }

    fun invalidateIcons() = synchronized(iconCache) { iconCache.clear() }

    companion object {
        const val TAG = "Glass"
    }
}

/**
 * Rasterises an app icon once to 112x112 px (56 dp) inside the tile squircle. Resources are asked
 * for at DENSITY_XXHIGH so a 144 px asset is downsampled, not a 48 px one upsampled; adaptive icons
 * are composited full-bleed (the system mask would leave a circle inside our squircle).
 */
object IconRaster {
    const val SIZE = 112
    private const val MIN_ASSET_PX = 96

    private val mask: Path by lazy {
        RoundedPolygon.rectangle(
            width = SIZE.toFloat(), height = SIZE.toFloat(),
            rounding = CornerRounding(28f, 0.6f),
            centerX = SIZE / 2f, centerY = SIZE / 2f,
        ).toPath()
    }

    fun render(context: Context, ai: ApplicationInfo, ri: ResolveInfo): ImageBitmap? {
        val pm = context.packageManager
        val drawable: Drawable = loadDrawable(pm, ai, ri) ?: return null
        if (drawable is BitmapDrawable && drawable.intrinsicWidth in 1 until MIN_ASSET_PX) {
            Log.i(AppCatalog.TAG, "icon ${ai.packageName}: only ${drawable.intrinsicWidth}px, monogram")
            return null
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.clipPath(mask)
        if (drawable is AdaptiveIconDrawable) {
            // Layers are 108 dp with the visible 72 dp in the middle: scale so that window fills the tile.
            val inset = (SIZE * 108f / 72f - SIZE) / 2f
            val bounds = Rect((-inset).toInt(), (-inset).toInt(), (SIZE + inset).toInt(), (SIZE + inset).toInt())
            drawable.background?.let { it.bounds = bounds; it.draw(canvas) }
            drawable.foreground?.let { it.bounds = bounds; it.draw(canvas) }
        } else {
            drawable.setBounds(0, 0, SIZE, SIZE)
            drawable.draw(canvas)
        }
        return bmp.asImageBitmap()
    }

    private fun loadDrawable(pm: PackageManager, ai: ApplicationInfo, ri: ResolveInfo): Drawable? {
        val res = ai.icon
        if (res != 0) {
            runCatching {
                pm.getResourcesForApplication(ai).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXHIGH, null)
            }.getOrNull()?.let { return it }
        }
        return runCatching { ri.loadIcon(pm) }.getOrNull() ?: runCatching { ai.loadIcon(pm) }.getOrNull()
    }
}

/** Refreshes the catalog when apps come and go, so Plex shows up the moment it is installed. */
class PackageChangeReceiver(private val catalog: AppCatalog) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        catalog.invalidateIcons()
        catalog.refresh()
    }

    companion object {
        fun register(context: Context, catalog: AppCatalog) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(context, PackageChangeReceiver(catalog), filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }
}
