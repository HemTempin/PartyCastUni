package ru.example.ymqueue

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Metadata we need to identify the exact row in Yandex Music UI. */
data class TrackInfo(
    val trackId: String,
    val apiTrackId: String,
    val title: String,
    val artists: List<String>,
    val albumTitle: String? = null,
    val durationMs: Long? = null
)

/**
 * Uses the same public-facing API shape as MarshalX/yandex-music-api:
 * POST https://api.music.yandex.net/tracks with form parameter track-ids.
 *
 * For album links Yandex expects the id in "trackId:albumId" form.
 */
object YandexTrackApiResolver {
    fun resolve(link: TrackLinkParser.NormalizedTrackLink): TrackInfo? {
        val apiTrackId = if (link.wasAlbumTrackLink && !link.albumId.isNullOrBlank()) {
            "${link.trackId}:${link.albumId}"
        } else {
            link.trackId
        }
        return resolve(link.trackId, apiTrackId)
    }

    fun resolve(trackId: String, apiTrackId: String = trackId): TrackInfo? {
        val body = "track-ids=${URLEncoder.encode(apiTrackId, "UTF-8")}&with-positions=true"
        val conn = (URL("https://api.music.yandex.net/tracks").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 10000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
            setRequestProperty("User-Agent", "Yandex-Music-API")
            setRequestProperty("Accept-Language", "ru")
        }

        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            if (conn.responseCode !in 200..299 || text.isBlank()) return null

            val json = JSONObject(text)
            val result = json.optJSONArray("result") ?: return null
            if (result.length() == 0) return null
            val track = result.optJSONObject(0) ?: return null

            val title = track.optString("title").trim()
            if (title.isBlank()) return null

            val artistsJson = track.optJSONArray("artists")
            val artists = mutableListOf<String>()
            if (artistsJson != null) {
                for (i in 0 until artistsJson.length()) {
                    val name = artistsJson.optJSONObject(i)?.optString("name")?.trim().orEmpty()
                    if (name.isNotBlank()) artists += name
                }
            }

            val albumsJson = track.optJSONArray("albums")
            val albumTitle = albumsJson?.optJSONObject(0)?.optString("title")?.trim()?.takeIf { it.isNotBlank() }
            val durationMs = if (track.has("durationMs")) track.optLong("durationMs") else null

            TrackInfo(
                trackId = trackId,
                apiTrackId = apiTrackId,
                title = title,
                artists = artists,
                albumTitle = albumTitle,
                durationMs = durationMs
            )
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
