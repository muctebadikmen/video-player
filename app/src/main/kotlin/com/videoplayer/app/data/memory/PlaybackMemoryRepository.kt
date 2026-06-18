package com.videoplayer.app.data.memory

import com.videoplayer.core.playback.effectiveResumePosition
import com.videoplayer.core.playback.resolvePreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Narrow read seam the library depends on, so it can be faked in tests without Room. */
interface MemorySource {
    fun observeAll(): Flow<List<PlaybackMemoryEntity>>
}

/**
 * Resolves what settings to apply when a file opens, and persists state when it closes.
 *
 * Precedence (per-file → folder → global) via [resolvePreference]. The folder tier is
 * `null` in V1 (no folder-default setter yet); it is fully unit-tested in C1 and wired
 * here for when P1.D/P1.E add folder defaults. Resume position runs through the pure
 * [effectiveResumePosition] policy and is gated by the global resume-enabled setting.
 */
class PlaybackMemoryRepository(
    private val dao: PlaybackMemoryDao,
    private val settings: SettingsRepository,
) : MemorySource {
    suspend fun resolveStart(mediaUri: String): ResolvedStartSettings {
        val saved = dao.getByUri(mediaUri)
        val resumeEnabled = settings.resumeEnabled.first()
        val globalSpeed = settings.defaultSpeed.first()

        val speed = resolvePreference(file = saved?.speed, folder = null, global = globalSpeed)
        val aspectMode = resolvePreference(file = saved?.aspectMode, folder = null, global = "FIT")
        val startPositionMs = if (resumeEnabled && saved != null) {
            effectiveResumePosition(saved.positionMs, saved.durationMs)
        } else {
            0L
        }
        return ResolvedStartSettings(startPositionMs, speed, aspectMode, saved?.orientation)
    }

    suspend fun persist(
        mediaUri: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        aspectMode: String,
        nowEpochMs: Long,
    ) {
        // Never record a blank/unloaded state (e.g. read after engine release) — it would
        // clobber a previously saved resume position.
        if (durationMs <= 0L) return

        val existing = dao.getByUri(mediaUri)
        val base = existing ?: PlaybackMemoryEntity(
            mediaUri = mediaUri, positionMs = 0, durationMs = 0,
            aspectMode = "FIT", speed = 1f, updatedAtEpochMs = 0L,
        )
        dao.upsert(
            base.copy(
                positionMs = positionMs,
                durationMs = durationMs,
                speed = speed,
                aspectMode = aspectMode,
                updatedAtEpochMs = nowEpochMs,
            ),
        )
    }

    /**
     * Persists only the per-file orientation override, leaving position/duration/speed/aspect
     * untouched. Unlike [persist] there is no `durationMs <= 0` guard — orientation is a valid
     * standalone preference that can be set before any playback state exists.
     */
    suspend fun persistOrientation(mediaUri: String, orientation: Int?, nowEpochMs: Long) {
        val existing = dao.getByUri(mediaUri)
        val base = existing ?: PlaybackMemoryEntity(
            mediaUri = mediaUri, positionMs = 0, durationMs = 0,
            aspectMode = "FIT", speed = 1f, updatedAtEpochMs = 0L,
        )
        dao.upsert(base.copy(orientation = orientation, updatedAtEpochMs = nowEpochMs))
    }

    /** Observes every persisted memory row — used by the library for progress + continue-watching. */
    override fun observeAll(): Flow<List<PlaybackMemoryEntity>> = dao.observeAll()
}
