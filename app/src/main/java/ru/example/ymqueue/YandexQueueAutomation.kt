package ru.example.ymqueue

import android.content.Context
import android.content.Intent
import android.net.Uri

object YandexQueueAutomation {
    private const val YANDEX_MUSIC_PACKAGE = "ru.yandex.music"

    // Сейчас в UI Я.Музыки в строке альбомного трека обычно виден только title,
    // а artist рядом со строкой не отображается. Поэтому artist-фильтр временно
    // выключен, но код оставлен: если понадобится для другого сервиса/экрана,
    // достаточно переключить на true.
    private const val USE_ARTIST_FILTER_FOR_ROW_SEARCH = false

    fun addTrackToQueue(
        context: Context,
        url: String,
        expectedTrackId: String? = null,
        preferTrackRowMenu: Boolean = false,
        trackInfo: TrackInfo? = null
    ) {
        YandexAccessibilityService.captureReturnTarget()
        YandexAccessibilityService.pendingAction = YandexAccessibilityService.PendingAction.AddCurrentTrackToQueue(
            expectedTrackId = expectedTrackId,
            openedUrl = url,
            preferTrackRowMenu = preferTrackRowMenu,
            expectedTrackTitle = trackInfo?.title,
            expectedArtists = if (USE_ARTIST_FILTER_FOR_ROW_SEARCH) trackInfo?.artists.orEmpty() else emptyList()
        )
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(YANDEX_MUSIC_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        YandexAccessibilityService.kickPendingAction()
    }

    fun openTrack(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(YANDEX_MUSIC_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
