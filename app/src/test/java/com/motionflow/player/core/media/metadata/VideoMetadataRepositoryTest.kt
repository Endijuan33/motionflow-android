package com.motionflow.player.core.media.metadata

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the repository's reading policy: read a source once, reuse what is known, and never let a
 * description for a previous source outlive the new one.
 */
class VideoMetadataRepositoryTest {

    @Test
    fun `a first read reports progress and then the description`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        val emissions = repository.metadata(SOURCE).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first() is MetadataResult.Loading)
        assertTrue(emissions.last() is MetadataResult.Success)
        assertEquals(1, reader.metadataReads)
    }

    @Test
    fun `a source that has been described is served from the cache`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        repository.metadata(SOURCE).toList()
        val second = repository.metadata(SOURCE).toList()

        assertEquals("the file must not be read twice", 1, reader.metadataReads)
        assertEquals(1, second.size)
        assertTrue(
            "a cached description is published straight away, without a loading state",
            second.single() is MetadataResult.Success,
        )
    }

    @Test
    fun `describing another source replaces the cached one`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        repository.metadata(SOURCE).toList()
        repository.metadata(OTHER_SOURCE).toList()
        repository.metadata(OTHER_SOURCE).toList()

        assertEquals("one read per source, plus one for the repeat", 2, reader.metadataReads)
    }

    @Test
    fun `the player's parsed formats refresh a description that lacked them, once`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        repository.metadata(SOURCE).toList()
        repository.metadata(SOURCE, hint = HINT).toList()
        repository.metadata(SOURCE, hint = HINT).toList()

        assertEquals("the hint is applied once, not on every callback", 2, reader.metadataReads)
        assertEquals(HINT, reader.lastHint)
    }

    @Test
    fun `a failed read is reported and can be retried`() = runTest {
        val reader = FakeReader().apply {
            failure = MetadataReadException(MetadataError.UNSUPPORTED_FORMAT)
        }
        val repository = VideoMetadataRepository(reader)

        val failed = repository.metadata(SOURCE).toList()
        assertEquals(MetadataError.UNSUPPORTED_FORMAT, (failed.last() as MetadataResult.Error).error)

        reader.failure = null
        val retried = repository.metadata(SOURCE).toList()

        assertTrue("a failure must not be cached", retried.last() is MetadataResult.Success)
        assertEquals(2, reader.metadataReads)
    }

    @Test
    fun `an unclassified failure is still reported as an error`() = runTest {
        val reader = FakeReader().apply { failure = IllegalStateException("boom") }
        val repository = VideoMetadataRepository(reader)

        val emissions = repository.metadata(SOURCE).toList()

        assertEquals(MetadataError.UNKNOWN, (emissions.last() as MetadataResult.Error).error)
    }

    @Test
    fun `the document is queried once and handed to the metadata read`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        val document = repository.document(SOURCE)
        repository.metadata(SOURCE).toList()

        assertEquals("clip.mp4", document.title)
        assertEquals("the provider must not be asked twice", 1, reader.documentReads)
        assertEquals("clip.mp4", reader.lastDocumentInfo?.title)
    }

    @Test
    fun `a document is reused for the same source and re-read for another`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        repository.document(SOURCE)
        repository.document(SOURCE)
        assertEquals(1, reader.documentReads)

        repository.document(OTHER_SOURCE)
        assertEquals(2, reader.documentReads)
    }

    @Test
    fun `a read that produced nothing useful still reports success with unknown fields`() = runTest {
        val reader = FakeReader()
        val repository = VideoMetadataRepository(reader)

        val metadata = (repository.metadata(SOURCE).toList().last() as MetadataResult.Success).metadata

        assertNull(metadata.audio)
        assertEquals("clip.mp4", metadata.title)
    }
}

private const val SOURCE = "content://sample/one.mp4"
private const val OTHER_SOURCE = "content://sample/two.mp4"

private val HINT = TrackFormatHint(videoMimeType = "video/avc", width = 1920, height = 1080)

private class FakeReader : VideoMetadataReader {

    var documentInfo = MediaDocumentInfo(
        title = "clip.mp4",
        sizeBytes = 1_000L,
        mimeType = "video/mp4",
    )

    var failure: Throwable? = null

    var documentReads = 0
        private set

    var metadataReads = 0
        private set

    var lastDocumentInfo: MediaDocumentInfo? = null
        private set

    var lastHint: TrackFormatHint? = null
        private set

    override suspend fun document(sourceUri: String): MediaDocumentInfo {
        documentReads++
        return documentInfo
    }

    override suspend fun read(
        sourceUri: String,
        documentInfo: MediaDocumentInfo?,
        hint: TrackFormatHint?,
    ): VideoMetadata {
        metadataReads++
        lastDocumentInfo = documentInfo
        lastHint = hint
        failure?.let { throw it }

        return VideoMetadata(
            sourceUri = sourceUri,
            title = documentInfo?.title,
            fileSizeBytes = documentInfo?.sizeBytes,
        )
    }
}
