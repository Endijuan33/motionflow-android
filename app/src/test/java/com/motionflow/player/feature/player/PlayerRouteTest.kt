package com.motionflow.player.feature.player

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that a media source survives the trip through a navigation route.
 *
 * This is the one part of the playback flow that cannot be checked by compiling: a source that does
 * not round trip still builds, and only fails later as unplayable media. Navigation decodes path
 * arguments exactly once, so the encoding is validated here against an independent decoder.
 */
class PlayerRouteTest {

    @Test
    fun `route keeps the whole source in one path segment`() {
        val route = PlayerRoute.create(SAMPLE_CONTENT_URI)
        val segments = route.split('/')

        assertEquals(2, segments.size)
        assertEquals("player", segments[0])
        assertFalse("the separator must not survive into the segment", segments[1].contains(':'))
        assertFalse("a new path segment would change the route", route.removePrefix("player/").contains('/'))
    }

    @Test
    fun `encoded sources survive a standard percent-decoding pass`() {
        SOURCES.forEach { source ->
            val encoded = PlayerRoute.encode(source)
            val decoded = URI("http://motionflow/$encoded").path.removePrefix("/")

            assertEquals("failed to round trip: $source", source, decoded)
        }
    }

    @Test
    fun `unsupported schemes are rejected before reaching the player`() {
        assertTrue(PlayerRoute.isSupportedSource("content://media/external/video/media/42"))
        assertTrue(PlayerRoute.isSupportedSource("/sdcard/movie.mp4".withFileScheme()))

        assertFalse(PlayerRoute.isSupportedSource("https://example.com/movie.mp4"))
        assertFalse(PlayerRoute.isSupportedSource("rtsp://example.com/stream"))
        assertFalse(PlayerRoute.isSupportedSource(""))
        assertFalse(PlayerRoute.isSupportedSource("movie.mp4"))
    }

    private fun String.withFileScheme(): String = "file://$this"

    private companion object {
        const val SAMPLE_CONTENT_URI =
            "content://com.android.providers.media.documents/document/video%3A1234"

        val SOURCES = listOf(
            SAMPLE_CONTENT_URI,
            "content://media/external/video/media/42",
            "file:///storage/emulated/0/Movies/My Video #1 (2026).mp4",
            "file:///storage/emulated/0/Movies/a+b&c=d.mp4",
            "content://provider/%E6%97%A5%E6%9C%AC%E8%AA%9E.mp4",
            "file:///storage/emulated/0/100%_sure.mp4",
        )
    }
}
