package com.motionflow.player.core.media.metadata

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves metadata for media sources, reading each source once.
 *
 * The cache holds a single entry on purpose: the player surface describes the video it is showing,
 * and a history of technical descriptions would be state kept for no one. It is also what stops a
 * re-entered screen, a recomposition or a retry from re-reading a file it has already described.
 *
 * Failures are deliberately not cached. A file that could not be read once may be readable after the
 * user grants access again, and the retry path depends on that.
 *
 * This class is process-scoped (held by the application), so the entry survives navigation between
 * the player and any future library screen.
 */
class VideoMetadataRepository(private val reader: VideoMetadataReader) {

    private val mutex = Mutex()
    private var entry: CacheEntry? = null

    /**
     * Emits the state of the metadata read for [sourceUri].
     *
     * Emits the cached description straight away when one is held, otherwise `Loading` followed by
     * the result. Nothing here blocks the caller: collection is the caller's coroutine to cancel.
     */
    fun metadata(sourceUri: String, hint: TrackFormatHint? = null): Flow<MetadataResult> = flow {
        val cached = cachedMetadata(sourceUri, hint)
        if (cached != null) {
            emit(MetadataResult.Success(cached))
            return@flow
        }

        emit(MetadataResult.Loading)
        emit(readMetadata(sourceUri, hint))
    }

    /** The document label, size and MIME type, read once per source and reused afterwards. */
    suspend fun document(sourceUri: String): MediaDocumentInfo {
        cachedDocument(sourceUri)?.let { return it }

        val document = reader.document(sourceUri)
        mutex.withLock {
            val current = entry
            entry = if (current?.sourceUri == sourceUri) {
                current.copy(document = document)
            } else {
                CacheEntry(sourceUri = sourceUri, document = document)
            }
        }
        return document
    }

    private suspend fun cachedMetadata(sourceUri: String, hint: TrackFormatHint?): VideoMetadata? =
        mutex.withLock {
            val current = entry ?: return@withLock null
            if (current.sourceUri != sourceUri) return@withLock null
            val metadata = current.metadata ?: return@withLock null
            // A description read without the player's parsed formats can be improved by them, so
            // such a request refreshes it; anything else is served from the cache.
            if (hint != null && !current.includedFormatHint) return@withLock null
            metadata
        }

    private suspend fun cachedDocument(sourceUri: String): MediaDocumentInfo? = mutex.withLock {
        val current = entry ?: return@withLock null
        if (current.sourceUri != sourceUri) return@withLock null
        current.document
    }

    private suspend fun readMetadata(sourceUri: String, hint: TrackFormatHint?): MetadataResult = try {
        val metadata = reader.read(
            sourceUri = sourceUri,
            documentInfo = cachedDocument(sourceUri),
            hint = hint,
        )
        mutex.withLock {
            entry = CacheEntry(
                sourceUri = sourceUri,
                document = entry?.takeIf { it.sourceUri == sourceUri }?.document,
                metadata = metadata,
                includedFormatHint = hint != null,
            )
        }
        MetadataResult.Success(metadata)
    } catch (failure: MetadataReadException) {
        Log.w(TAG, "Metadata read failed: ${failure.error}", failure)
        MetadataResult.Error(failure.error)
    } catch (failure: Exception) {
        Log.w(TAG, "Metadata read failed", failure)
        MetadataResult.Error(MetadataError.from(failure))
    }

    private data class CacheEntry(
        val sourceUri: String,
        val document: MediaDocumentInfo? = null,
        val metadata: VideoMetadata? = null,
        val includedFormatHint: Boolean = false,
    )

    companion object {

        private const val TAG = "MotionFlowMetadata"

        /** The production repository: provider lookups, container headers and a timestamp probe. */
        fun from(context: Context): VideoMetadataRepository =
            VideoMetadataRepository(AndroidVideoMetadataReader(context.applicationContext))
    }
}
