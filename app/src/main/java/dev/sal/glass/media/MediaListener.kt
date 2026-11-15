package dev.sal.glass.media

import android.service.notification.NotificationListenerService

/**
 * Here only so Android shares the apps' media sessions with the launcher: getActiveSessions answers
 * an enabled notification listener and nothing else short of the privileged MEDIA_CONTENT_CONTROL.
 * It reads no notifications. Switched on over adb, never on screen:
 *
 *     adb shell cmd notification allow_listener dev.sal.glass/dev.sal.glass.media.MediaListener
 */
class MediaListener : NotificationListenerService()
