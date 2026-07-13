package ru.example.ymqueue

/**
 * Parses Yandex Music links and keeps enough context for Accessibility automation.
 *
 * Important: for album links we DO NOT rewrite /album/{albumId}/track/{trackId} to /track/{trackId}.
 * In the real app that rewrite can still land in the album context, but it loses the most useful
 * signal: the original route that points to the concrete track inside the album. The Accessibility
 * service uses wasAlbumTrackLink to avoid the album-level overflow button and to open a row-level
 * menu instead. Row-level menu maps to a single TrackPlayable (one wdt), while album-level menu maps
 * to a List<wdt> for the whole album.
 */
object TrackLinkParser {
    private val trackIdRegex = Regex("""(?:^|/)track/(\d+)(?=[:/?#&\s]|$)""", RegexOption.IGNORE_CASE)
    private val albumTrackRegex = Regex("""/album/(\d+)/track/(\d+)""", RegexOption.IGNORE_CASE)

    data class NormalizedTrackLink(
        val originalText: String,
        val trackId: String,
        val url: String,
        val wasAlbumTrackLink: Boolean,
        val albumId: String? = null
    )

    fun normalizeTrackUrl(text: String): NormalizedTrackLink? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        val albumMatch = albumTrackRegex.find(raw)
        if (albumMatch != null) {
            val albumId = albumMatch.groupValues[1]
            val trackId = albumMatch.groupValues[2]
            return NormalizedTrackLink(
                originalText = raw,
                trackId = trackId,
                url = raw,
                wasAlbumTrackLink = true,
                albumId = albumId
            )
        }

        val match = trackIdRegex.find(raw) ?: return null
        val trackId = match.groupValues[1]
        return NormalizedTrackLink(
            originalText = raw,
            trackId = trackId,
            url = raw,
            wasAlbumTrackLink = false,
            albumId = null
        )
    }

    /** Backward-compatible helper used by earlier versions of MainActivity. */
    fun extractTrackUrl(text: String): String? = normalizeTrackUrl(text)?.url

    fun buildStandaloneTrackUrl(trackId: String): String = "https://music.yandex.ru/track/$trackId"
}
