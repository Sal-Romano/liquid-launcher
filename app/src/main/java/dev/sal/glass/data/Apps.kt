package dev.sal.glass.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** One launchable TV app, as the launcher shows it. */
data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    /** The 16:9 Leanback banner when the app ships one (YouTube, Luna, Spotify); null otherwise (Amazon Music). */
    val banner: Drawable?,
    /** The square app icon, for apps without a banner. */
    val icon: Drawable,
) {
    val launchIntent: Intent
        get() = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(packageName, activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * Lists the TV's launchable apps. Needs QUERY_ALL_PACKAGES on API 30+, which the manifest declares.
 *
 * Fire OS peculiarities seen on the Hisense U6 (2026-11-01): com.mediatek.opapp exposes ~18 satellite
 * operator activities that are useless here; the launcher-helper apps are ours or redundant.
 */
object Apps {
    /** Packages never shown, whatever the config says. */
    val HIDDEN_PACKAGES = setOf(
        "com.mediatek.opapp",
        "io.github.toolicious.homeonfire",
        "dev.sal.projectivy.localwallpapers",
        "dev.sal.glass",
    )

    fun launchable(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .filter { it.activityInfo.packageName !in HIDDEN_PACKAGES }
            // Some packages list several Leanback activities; keep the first per package.
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val ai = info.activityInfo
                AppEntry(
                    packageName = ai.packageName,
                    activityName = ai.name,
                    label = info.loadLabel(pm).toString(),
                    banner = ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm),
                    icon = info.loadIcon(pm),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getPackageInfo(packageName, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
