package dev.sal.glass.phone

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.sal.glass.data.PhotoInfo
import dev.sal.glass.data.PhotoInfos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What the info card shows about a photo, gathered once and kept in [PhotoInfos]: what is already
 * known (who sent it from a phone and what the phone read from it, or a seed from the originals),
 * the file's own EXIF for the gaps, and the place through the relay, from the coordinates or, for a
 * wallpaper without any, from its name. The map is fetched once per spot and kept in the cache.
 */
object Insights {
    suspend fun about(file: File, link: PhoneLink): PhotoInfo = withContext(Dispatchers.IO) {
        var info = PhotoInfos.get(file.name) ?: PhotoInfo()
        if (!info.exifRead) {
            info = info.fill(PhotoInfos.readExif(file)).copy(exifRead = true)
            PhotoInfos.put(file.name, info)
        }
        if (info.place == null && !info.nowhere) {
            val lat = info.lat
            val lon = info.lon
            val answer = when {
                lat != null && lon != null -> link.place(lat, lon)
                else -> PhotoInfos.placeQuery(file)?.let { link.place(it) } ?: PhoneLink.PlaceAnswer.Nowhere
            }
            when (answer) {
                is PhoneLink.PlaceAnswer.Found -> info = info.copy(place = answer.place)
                PhoneLink.PlaceAnswer.Nowhere -> info = info.copy(nowhere = true)
                // No relay or no maps key yet: nothing is stored, so it is asked again next time.
                PhoneLink.PlaceAnswer.Unavailable -> return@withContext info
            }
            PhotoInfos.put(file.name, info)
        }
        info
    }

    /** The map around [info]'s spot, from the cache or through the relay; null without a spot or a map. */
    suspend fun map(context: Context, info: PhotoInfo, link: PhoneLink): ImageBitmap? = withContext(Dispatchers.IO) {
        val (lat, lon) = info.where ?: return@withContext null
        val dir = File(context.cacheDir, "maps").apply { mkdirs() }
        val file = File(dir, String.format(Locale.US, "%.2f_%.2f_4.png", lat, lon))
        if (!file.exists()) {
            val bytes = link.map(lat, lon) ?: return@withContext null
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }
}
