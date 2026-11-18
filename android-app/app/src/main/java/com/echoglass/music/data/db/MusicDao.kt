package com.echoglass.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Transaction
    @Query("SELECT * FROM listening_sessions ORDER BY startedAt DESC")
    fun observeAllSessionsWithTrack(): Flow<List<SessionWithTrack>>

    @Query("SELECT * FROM tracks WHERE signature = :signature LIMIT 1")
    suspend fun findTrackBySignature(signature: String): TrackEntity?

    @Query("SELECT targetTrackId FROM track_aliases WHERE aliasSignature = :signature LIMIT 1")
    suspend fun findTargetTrackIdByAlias(signature: String): Long?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: TrackAliasEntity)

    @Insert
    suspend fun insertSession(session: ListeningSessionEntity): Long

    @Query("UPDATE listening_sessions SET trackId = :targetId WHERE trackId = :sourceId")
    suspend fun reassignSessions(sourceId: Long, targetId: Long)

    @Query("UPDATE track_aliases SET targetTrackId = :targetId WHERE targetTrackId = :sourceId")
    suspend fun reassignAliases(sourceId: Long, targetId: Long)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: Long)

    @Transaction
    suspend fun mergeTracks(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        val source = getTrackById(sourceId) ?: return
        val target = getTrackById(targetId) ?: return

        val enrichedTarget = target.copy(
            artist = if (target.artist == "Unknown" && source.artist != "Unknown") source.artist else target.artist,
            album = if (target.album == "Unknown" && source.album != "Unknown") source.album else target.album,
            albumArtist = if (target.albumArtist == "Unknown" && source.albumArtist != "Unknown") source.albumArtist else target.albumArtist,
            genre = if (target.genre == "Unknown" && source.genre != "Unknown") source.genre else target.genre,
            year = if (target.year == "Unknown" && source.year != "Unknown") source.year else target.year,
            trackNumber = if (target.trackNumber == "Unknown" && source.trackNumber != "Unknown") source.trackNumber else target.trackNumber,
            durationMs = if (target.durationMs == 0L && source.durationMs > 0L) source.durationMs else target.durationMs,
            albumArtPath = target.albumArtPath ?: source.albumArtPath
        )
        updateTrack(enrichedTarget)
        reassignSessions(sourceId, targetId)
        reassignAliases(sourceId, targetId)
        insertAlias(TrackAliasEntity(aliasSignature = source.signature, targetTrackId = targetId))
        deleteTrackById(sourceId)
    }

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM track_aliases")
    suspend fun getAllAliasesSync(): List<TrackAliasEntity>

    @Query("SELECT * FROM listening_sessions")
    suspend fun getAllSessionsSync(): List<ListeningSessionEntity>

    @Query("DELETE FROM listening_sessions")
    suspend fun clearSessions()

    @Query("DELETE FROM track_aliases")
    suspend fun clearAliases()

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAliases(aliases: List<TrackAliasEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<ListeningSessionEntity>)

    @Transaction
    suspend fun restoreBackup(
        tracks: List<TrackEntity>,
        aliases: List<TrackAliasEntity>,
        sessions: List<ListeningSessionEntity>
    ) {
        clearSessions()
        clearAliases()
        clearTracks()
        insertTracks(tracks)
        insertAliases(aliases)
        insertSessions(sessions)
    }
}
