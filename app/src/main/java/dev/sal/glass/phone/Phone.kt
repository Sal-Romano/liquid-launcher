package dev.sal.glass.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** What the screen shows of the phone link. Written on the main thread by [PhoneLink]. */
object Phone {
    enum class Status { Off, Connecting, Online, Offline }

    var status by mutableStateOf(Status.Off)
        internal set
    /** Why the last connection ended, in a few words; cleared once connected. */
    var problem by mutableStateOf<String?>(null)
        internal set
    /** What the QR code says: the relay's page with this TV's id and send key in the fragment. */
    var url by mutableStateOf<String?>(null)
        internal set
    /** What to say on the TV about the latest photo ("Test from iPhone · not saved"); [notices] counts them. */
    var notice by mutableStateOf<String?>(null)
        internal set
    /** The photo is about to go up: Settings or the Wallpapers screen should get out of its way. */
    var noticeClearsScreen = false
        internal set
    var notices by mutableLongStateOf(0L)
        internal set
    /** The home screen is in front (the activity sets it); a photo put up behind an app says so. */
    @Volatile var foreground = false
}

/**
 * This TV's identity at the relay, made once and kept in files/phone.json. [tvId] and [sendKey]
 * are in the QR code; [secret] never leaves the TV except to the relay, which keeps only its hash.
 */
@Serializable
internal data class PhoneKeys(val tvId: String, val secret: String, val sendKey: String) {
    fun valid() = TV_ID.matches(tvId) && KEY.matches(secret) && secret.length >= 32 && KEY.matches(sendKey)

    companion object {
        private val TV_ID = Regex("[a-z2-7]{16}")
        private val KEY = Regex("[A-Za-z0-9_-]{16,128}")
        private val random = SecureRandom()
        private val json = Json { ignoreUnknownKeys = true }

        /** 80-bit id in base 32, 256-bit secret and 128-bit send key in base64url. */
        fun fresh() = PhoneKeys(tvId = base32(16), secret = token(32), sendKey = token(16))

        fun newSendKey() = token(16)

        fun load(file: File): PhoneKeys? =
            runCatching { json.decodeFromString<PhoneKeys>(file.readText()) }.getOrNull()?.takeIf { it.valid() }

        fun save(file: File, keys: PhoneKeys) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), keys))
            if (!tmp.renameTo(file)) tmp.delete()
        }

        private fun token(bytes: Int): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        private fun base32(chars: Int): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            return buildString { repeat(chars) { append(alphabet[random.nextInt(32)]) } }
        }
    }
}
