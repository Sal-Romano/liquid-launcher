package dev.sal.glass.data

import android.content.Context
import android.media.ExifInterface
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Where a photo was taken, as the relay's place lookup describes it (Google's geocoding). */
@Serializable
data class Place(
    val name: String,
    val locality: String? = null,
    val region: String? = null,
    val country: String? = null,
    /** ISO 3166-1 alpha-2, for the flag. */
    val countryCode: String? = null,
    val lat: Double,
    val lon: Double,
)

/**
 * What the info card says about a photo. A photo from a phone arrives with who sent it and what
 * the phone read from it before rendering (the rendered copy carries no EXIF); any photo's own EXIF
 * fills the gaps; the place comes from the relay, from the coordinates, or for a wallpaper with
 * none from its file name ("aoraki-mount-cook").
 */
@Serializable
data class PhotoInfo(
    /** The name of whoever sent it from a phone. */
    val from: String? = null,
    /** When it arrived, epoch ms. */
    val sentAt: Long? = null,
    /** EXIF local time, "2024-07-14T18:22:05", with "+02:00" when the camera wrote the offset. */
    val takenAt: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val camera: String? = null,
    val place: Place? = null,
    /** The file's own EXIF has been read into this (once is enough). */
    val exifRead: Boolean = false,
    /** A place lookup came back empty: not asked again. */
    val nowhere: Boolean = false,
) {
    /** Coordinates to centre a map on: the photo's own, else the place found for it. */
    val where: Pair<Double, Double>? get() = if (lat != null && lon != null) lat to lon else place?.let { it.lat to it.lon }

    /** [other]'s fields where this one has none. */
    fun fill(other: PhotoInfo) = PhotoInfo(
        from = from ?: other.from,
        sentAt = sentAt ?: other.sentAt,
        takenAt = takenAt ?: other.takenAt,
        lat = lat ?: other.lat,
        lon = lon ?: other.lon,
        camera = camera ?: other.camera,
        place = place ?: other.place,
        exifRead = exifRead || other.exifRead,
        nowhere = nowhere && other.nowhere,
    )
}

/**
 * Every photo's [PhotoInfo], by file name, in files/photo-info.json (written whole, through a temp
 * file). A files/photo-info-seed.json pushed over adb (tools/photo-info) is merged in at start, only
 * filling gaps, then deleted. Touched from the UI and the phone link's thread, so synchronized.
 */
object PhotoInfos {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var file: File? = null
    private val infos = HashMap<String, PhotoInfo>()

    @Synchronized
    fun init(context: Context) {
        val f = File(context.filesDir, "photo-info.json").also { file = it }
        if (f.exists()) runCatching { infos.putAll(json.decodeFromString<Map<String, PhotoInfo>>(f.readText())) }
            .onFailure { Log.w("Glass", "photo-info.json unreadable, starting afresh", it) }
        val seed = File(context.filesDir, "photo-info-seed.json")
        if (seed.exists()) {
            runCatching { json.decodeFromString<Map<String, PhotoInfo>>(seed.readText()) }
                .onSuccess { seeded ->
                    seeded.forEach { (name, info) -> infos[name] = infos[name]?.fill(info) ?: info }
                    Log.i("Glass", "photo info: ${seeded.size} entries seeded")
                    save()
                }
                .onFailure { Log.w("Glass", "photo-info-seed.json unreadable", it) }
            seed.delete()
        }
    }

    @Synchronized
    fun get(name: String): PhotoInfo? = infos[name]

    @Synchronized
    fun put(name: String, info: PhotoInfo) {
        infos[name] = info
        save()
    }

    /** The entry for [from] (a test from a phone) now belongs to [to] (its saved copy). */
    @Synchronized
    fun rename(from: String, to: String) {
        infos.remove(from)?.let { infos[to] = it; save() }
    }

    @Synchronized
    fun remove(name: String) {
        if (infos.remove(name) != null) save()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(json.encodeToString(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), PhotoInfo.serializer()), infos))
            if (!tmp.renameTo(f)) error("rename failed")
        }.onFailure { Log.w("Glass", "could not save photo-info.json", it) }
    }

    /** What [file]'s own EXIF says: when, where and with what. Nothing for a photo without it. */
    fun readExif(file: File): PhotoInfo = runCatching {
        val exif = ExifInterface(file.path)
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
        val takenAt = raw?.let { Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""").find(it) }
            ?.destructured?.let { (y, mo, d, h, mi, s) -> "$y-$mo-${d}T$h:$mi:$s" + (offset?.takeIf { o -> Regex("""[+-]\d{2}:\d{2}""").matches(o) } ?: "") }
        @Suppress("DEPRECATION") // the float form is the one every API level has
        val ll = FloatArray(2).takeIf { exif.getLatLong(it) && !(it[0] == 0f && it[1] == 0f) }
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = when {
            model.isEmpty() -> make.ifEmpty { null }
            // "NIKON CORPORATION" + "NIKON D750" is a NIKON D750.
            make.isEmpty() || model.startsWith(make.substringBefore(' '), ignoreCase = true) -> model
            else -> "$make $model"
        }
        PhotoInfo(takenAt = takenAt, lat = ll?.get(0)?.toDouble(), lon = ll?.get(1)?.toDouble(), camera = camera, exifRead = true)
    }.getOrElse { PhotoInfo(exifRead = true) }

    /**
     * A wallpaper's file name as words for a place lookup: "city-hong-kong-harbour-night" is
     * "hong kong harbour", "llyn-ogwen-milky-way" is "llyn ogwen". Null for a phone's photo (its name
     * is a time) or a name with nothing left.
     */
    fun placeQuery(file: File): String? {
        if (Wallpapers.isFromPhone(file)) return null
        val words = file.nameWithoutExtension.lowercase().split('-', '_', ' ', '.')
            .filter { it.isNotBlank() && it !in NOISE && it.none(Char::isDigit) }
        return words.joinToString(" ").ifBlank { null }
    }

    /** Words in wallpaper names that say when or how, not where. */
    private val NOISE = setOf(
        "city", "night", "dusk", "dawn", "sunrise", "sunset", "morning", "evening", "aerial", "skyline",
        "view", "panorama", "milky", "way", "from", "the", "at", "of", "img", "dsc", "photo", "wallpaper",
    )
}
