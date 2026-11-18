package com.echoglass.music.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["signature"], unique = true)]
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val signature: String,
    val title: String = "Unknown",
    val artist: String = "Unknown",
    val album: String = "Unknown",
    val albumArtist: String = "Unknown",
    val genre: String = "Unknown",
    val year: String = "Unknown",
    val trackNumber: String = "Unknown",
    val durationMs: Long = 0L,
    val albumArtPath: String? = null,
    val extraMetadataJson: String = "{}"
)

@Serializable
@Entity(
    tableName = "track_aliases",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetTrackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("targetTrackId")]
)
data class TrackAliasEntity(
    @PrimaryKey val aliasSignature: String,
    val targetTrackId: Long
)

@Serializable
@Entity(
    tableName = "listening_sessions",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("startedAt")]
)
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val listenedDurationMs: Long,
    val lastTimelinePosMs: Long,
    val sourcePackage: String = "Unknown"
)

data class SessionWithTrack(
    @Embedded val session: ListeningSessionEntity,
    @Relation(parentColumn = "trackId", entityColumn = "id")
    val track: TrackEntity
)
