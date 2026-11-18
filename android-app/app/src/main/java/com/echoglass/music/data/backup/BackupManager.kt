package com.echoglass.music.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.echoglass.music.data.db.ListeningSessionEntity
import com.echoglass.music.data.db.MusicDao
import com.echoglass.music.data.db.TrackAliasEntity
import com.echoglass.music.data.db.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MusicBackupPayload(
    val version: Int = 1,
    val exportedAt: Long,
    val tracks: List<TrackEntity>,
    val aliases: List<TrackAliasEntity>,
    val sessions: List<ListeningSessionEntity>,
    val artworkBase64: Map<String, String> = emptyMap()
)

class BackupManager(private val context: Context, private val dao: MusicDao) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportToUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val tracks = dao.getAllTracksSync()
            val aliases = dao.getAllAliasesSync()
            val sessions = dao.getAllSessionsSync()

            val artMap = mutableMapOf<String, String>()
            tracks.mapNotNull { it.albumArtPath }.distinct().forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    artMap[file.name] = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
            }

            val payload = MusicBackupPayload(
                exportedAt = System.currentTimeMillis(),
                tracks = tracks,
                aliases = aliases,
                sessions = sessions,
                artworkBase64 = artMap
            )

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            } ?: error("Cannot open output stream")

            sessions.size
        }
    }

    suspend fun restoreFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: error("Cannot open backup file")

            val payload = json.decodeFromString<MusicBackupPayload>(raw)
            val artDir = File(context.filesDir, "album_art").apply { mkdirs() }

            val restoredPaths = mutableMapOf<String, String>()
            payload.artworkBase64.forEach { (fileName, b64) ->
                val outFile = File(artDir, fileName)
                outFile.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
                restoredPaths[fileName] = outFile.absolutePath
            }

            val fixedTracks = payload.tracks.map { track ->
                val oldName = track.albumArtPath?.let { File(it).name }
                track.copy(albumArtPath = oldName?.let { restoredPaths[it] })
            }

            dao.restoreBackup(fixedTracks, payload.aliases, payload.sessions)
            payload.sessions.size
        }
    }
}
