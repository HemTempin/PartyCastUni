package com.example.partycastmvp

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import ru.example.ymqueue.TrackInfo
import ru.example.ymqueue.TrackLinkParser
import ru.example.ymqueue.YandexAccessibilityService
import ru.example.ymqueue.YandexMediaController
import ru.example.ymqueue.YandexQueueAutomation
import ru.example.ymqueue.YandexTrackApiResolver

/**
 * Thin integration layer between the Java PartyCast host/player code and the
 * YMQueueController automation classes.
 */
object YandexMusicBridge {
    private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"

    fun interface LogCallback {
        fun onLog(message: String)
    }

    @JvmStatic
    fun addTrackAsNext(context: Context, rawUrl: String, callback: LogCallback?) {
        val appContext = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val originalText = rawUrl.trim()
        val normalized = TrackLinkParser.normalizeTrackUrl(originalText)
        val url = normalized?.url ?: originalText

        fun log(message: String) {
            main.post { callback?.onLog(message) }
        }

        fun startAutomation(
            expectedTrackId: String?,
            preferTrackRowMenu: Boolean,
            trackInfo: TrackInfo?,
            targetUrl: String = url
        ) {
            try {
                YandexQueueAutomation.addTrackToQueue(
                    context = appContext,
                    url = targetUrl,
                    expectedTrackId = expectedTrackId,
                    preferTrackRowMenu = preferTrackRowMenu,
                    trackInfo = trackInfo
                )

                main.postDelayed({
                    val action = YandexAccessibilityService.pendingAction
                    if (action is YandexAccessibilityService.PendingAction.AddCurrentTrackToQueue &&
                        action.openedUrl == targetUrl &&
                        !YandexAccessibilityService.isScenarioRunning()
                    ) {
                        log("Я.Музыка: первая попытка ещё не завершилась, мягко повторяю открытие ссылки")
                        try {
                            YandexQueueAutomation.addTrackToQueue(
                                context = appContext,
                                url = targetUrl,
                                expectedTrackId = expectedTrackId,
                                preferTrackRowMenu = preferTrackRowMenu,
                                trackInfo = trackInfo
                            )
                        } catch (t: Throwable) {
                            log("Я.Музыка: повторная попытка не запустилась — ${t.message ?: t.javaClass.simpleName}")
                        }
                    }
                }, if (preferTrackRowMenu) 5200L else 3600L)
            } catch (t: Throwable) {
                log("Я.Музыка: не удалось открыть или автоматизировать добавление — ${t.message ?: t.javaClass.simpleName}")
            }
        }

        if (url.isBlank()) {
            log("Я.Музыка: пустая ссылка, добавление отменено")
            return
        }

        if (normalized == null) {
            log("Я.Музыка: не смог выделить trackId, запускаю fallback-сценарий добавления по самой ссылке")
            main.post { startAutomation(null, false, null) }
            return
        }

        try {
            YandexQueueAutomation.openTrack(appContext, url)
            log("Я.Музыка: ссылка открыта, готовлю добавление следующим треком")
        } catch (t: Throwable) {
            log("Я.Музыка: не удалось открыть ссылку — ${t.message ?: t.javaClass.simpleName}")
        }

        val albumSuffix = normalized.albumId?.let { ", albumId=$it" }.orEmpty()
        log("Я.Музыка: готовлю добавление следующим, trackId=${normalized.trackId}$albumSuffix")

        Thread {
            val trackInfo: TrackInfo? = try {
                YandexTrackApiResolver.resolve(normalized)
                    ?: if (normalized.wasAlbumTrackLink) {
                        YandexTrackApiResolver.resolve(normalized.trackId)
                    } else {
                        null
                    }
            } catch (_: Throwable) {
                null
            }

            main.post {
                if (trackInfo != null) {
                    val artists = trackInfo.artists.joinToString(", ").ifBlank { "не указаны" }
                    val album = trackInfo.albumTitle?.let { ", album=$it" }.orEmpty()
                    callback?.onLog("Я.Музыка API: ${trackInfo.title} — $artists$album")
                    startAutomation(normalized.trackId, normalized.wasAlbumTrackLink, trackInfo)
                } else if (normalized.wasAlbumTrackLink) {
                    callback?.onLog(
                        "Я.Музыка: API не вернул title для album/track ссылки. " +
                            "Перехожу к безопасному одиночному track URL."
                    )
                    val standalone = TrackLinkParser.buildStandaloneTrackUrl(normalized.trackId)
                    startAutomation(normalized.trackId, false, null, standalone)
                } else {
                    callback?.onLog("Я.Музыка: API не вернул title/artist, запускаю старую стабильную логику для одиночного трека")
                    startAutomation(normalized.trackId, false, null)
                }
            }
        }.start()
    }

    @JvmStatic
    fun pause(context: Context) {
        YandexMediaController.pause(context.applicationContext)
    }

    @JvmStatic
    fun play(context: Context) {
        YandexMediaController.play(context.applicationContext)
    }

    @JvmStatic
    fun next(context: Context) {
        YandexMediaController.next(context.applicationContext)
    }

    @JvmStatic
    fun previous(context: Context) {
        YandexMediaController.previous(context.applicationContext)
    }

    @JvmStatic
    fun togglePlayPause(context: Context): Boolean {
        return if (isPlaying(context)) {
            pause(context)
            false
        } else {
            play(context)
            true
        }
    }

    @JvmStatic
    fun isPlaying(context: Context): Boolean = YandexMediaController.isPlaying(context.applicationContext)

    @JvmStatic
    fun currentTrackToken(context: Context): String = YandexMediaController.currentTrackToken(context.applicationContext)

    @JvmStatic
    fun currentTrackDetails(context: Context): Array<String> = YandexMediaController.currentTrackDetails(context.applicationContext)

    @JvmStatic
    fun status(context: Context): String = YandexMediaController.status(context.applicationContext)

    @JvmStatic
    fun openYandexMusic(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(YANDEX_MUSIC_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    @JvmStatic
    fun openNotificationListenerSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun accessibilityDiagnosticStatus(): String = YandexAccessibilityService.connectionStatus()

    @JvmStatic
    fun lastAccessibilityDiagnostic(): String = YandexAccessibilityService.lastDiagnosticReport

    @JvmStatic
    fun runAccessibilityDiagnostic(context: Context): Boolean {
        if (!YandexAccessibilityService.isServiceConnected()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(YANDEX_MUSIC_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return YandexAccessibilityService.requestDiagnostic()
    }
}
