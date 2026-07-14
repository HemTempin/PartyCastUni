package ru.example.ymqueue

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.SystemClock

object YandexMediaController {
    private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"

    private fun controller(context: Context): MediaController? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(context, YandexNotificationListenerService::class.java)
        return try {
            manager.getActiveSessions(cn).firstOrNull { it.packageName == YANDEX_MUSIC_PACKAGE }
        } catch (_: SecurityException) {
            null
        }
    }

    fun play(context: Context) = controller(context)?.transportControls?.play() ?: Unit
    fun pause(context: Context) = controller(context)?.transportControls?.pause() ?: Unit
    fun next(context: Context) = controller(context)?.transportControls?.skipToNext() ?: Unit
    fun previous(context: Context) = controller(context)?.transportControls?.skipToPrevious() ?: Unit
    fun isPlaying(context: Context): Boolean = controller(context)?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun currentTrackToken(context: Context): String {
        val metadata = controller(context)?.metadata ?: return ""
        val mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        return listOf(mediaId, title, artist, duration.toString()).joinToString("|")
    }

    fun seekBy(context: Context, deltaMs: Long) {
        val c = controller(context) ?: return
        val current = c.playbackState?.position ?: return
        val updated = (current + deltaMs).coerceAtLeast(0)
        c.transportControls.seekTo(updated)
    }

    fun status(context: Context): String {
        val c = controller(context) ?: return "Нет доступа к MediaSession. Включи доступ к уведомлениям."
        val md = c.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val state = c.playbackState?.state?.toString() ?: "unknown"
        val pos = c.playbackState?.position ?: 0L
        return "${artist.ifBlank { "?" }} — ${title.ifBlank { "?" }}\nstate=$state, pos=${pos / 1000}s, ts=${SystemClock.elapsedRealtime()}"
    }
}
