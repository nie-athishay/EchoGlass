package com.echoglass.music.tracker

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.echoglass.music.data.db.AppDatabase
import com.echoglass.music.data.db.ListeningSessionEntity
import com.echoglass.music.data.db.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class MusicNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaSessionManager: MediaSessionManager
    private val dao by lazy { AppDatabase.getInstance(applicationContext).musicDao() }

    private var activeController: MediaController? = null
    private val activeCallbacks = mutableMapOf<String, MediaController.Callback>()

    private var currentTrackId: Long? = null
    private var currentSourcePkg: String = "Unknown"
    private var playWallStartMs: Long = 0L
    private var playElapsedStartMs: Long = 0L
    private var lastTimelinePosMs: Long = 0L
    private var isPlaying: Boolean = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindControllers(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MusicNotificationListenerService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, component)
            bindControllers(mediaSessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
            // Permission not granted yet
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val notif = sbn.notification ?: return
        if (notif.category == Notification.CATEGORY_TRANSPORT ||
            notif.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        ) {
            val component = ComponentName(this, MusicNotificationListenerService::class.java)
            runCatching {
                bindControllers(mediaSessionManager.getActiveSessions(component), sbn)
            }
        }
    }

    private fun bindControllers(
        controllers: List<MediaController>,
        fallbackSbn: StatusBarNotification? = null
    ) {
        val currentPkgs = controllers.map { it.packageName }.toSet()
        activeCallbacks.keys.filterNot { it in currentPkgs }.forEach { pkg ->
            activeCallbacks.remove(pkg)
        }

        controllers.forEach { controller ->
            if (!activeCallbacks.containsKey(controller.packageName)) {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        handleStateOrMetadataUpdate(controller, state, controller.metadata, fallbackSbn)
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        handleStateOrMetadataUpdate(controller, controller.playbackState, metadata, fallbackSbn)
                    }

                    override fun onSessionDestroyed() {
                        if (activeController?.packageName == controller.packageName) {
                            flushCurrentPlayback()
                        }
                    }
                }
                controller.registerCallback(callback)
                activeCallbacks[controller.packageName] = callback
            }

            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                handleStateOrMetadataUpdate(controller, controller.playbackState, controller.metadata, fallbackSbn)
            }
        }
    }

    private fun handleStateOrMetadataUpdate(
        controller: MediaController,
        state: PlaybackState?,
        metadata: MediaMetadata?,
        sbn: StatusBarNotification?
    ) {
        val nowPlaying = state?.state == PlaybackState.STATE_PLAYING
        val timelinePos = state?.position?.coerceAtLeast(0L) ?: 0L
        val extracted = extractMetadata(controller.packageName, metadata, sbn, state)

        serviceScope.launch {
            if (extracted == null) {
                if (!nowPlaying && isPlaying && activeController?.packageName == controller.packageName) {
                    flushCurrentPlaybackSync()
                }
                return@launch
            }

            val trackId = resolveOrInsertTrack(extracted)
            if (trackId <= 0L) return@launch

            if (isPlaying && currentTrackId == trackId) {
                lastTimelinePosMs = timelinePos
                if (!nowPlaying) {
                    flushCurrentPlaybackSync()
                }
                return@launch
            }

            if (isPlaying) {
                flushCurrentPlaybackSync()
            }

            if (nowPlaying) {
                activeController = controller
                currentTrackId = trackId
                currentSourcePkg = controller.packageName.ifBlank { "Unknown" }
                playWallStartMs = System.currentTimeMillis()
                playElapsedStartMs = SystemClock.elapsedRealtime()
                lastTimelinePosMs = timelinePos
                isPlaying = true
            }
        }
    }

    private fun flushCurrentPlayback() {
        serviceScope.launch { flushCurrentPlaybackSync() }
    }

    private suspend fun flushCurrentPlaybackSync() {
        val trackId = currentTrackId ?: return
        if (!isPlaying) return

        val elapsedDuration = SystemClock.elapsedRealtime() - playElapsedStartMs
        val endedAt = System.currentTimeMillis()
        isPlaying = false
        currentTrackId = null

        if (elapsedDuration >= 3_000L) {
            dao.insertSession(
                ListeningSessionEntity(
                    trackId = trackId,
                    startedAt = playWallStartMs,
                    endedAt = endedAt,
                    listenedDurationMs = elapsedDuration,
                    lastTimelinePosMs = lastTimelinePosMs,
                    sourcePackage = currentSourcePkg
                )
            )
        }
    }

    private suspend fun resolveOrInsertTrack(data: ExtractedMetadata): Long {
        val aliasedId = dao.findTargetTrackIdByAlias(data.signature)
        if (aliasedId != null) return aliasedId

        val existing = dao.findTrackBySignature(data.signature)
        if (existing != null) {
            if (existing.albumArtPath == null && data.artPath != null) {
                dao.updateTrack(existing.copy(albumArtPath = data.artPath))
            }
            return existing.id
        }

        val entity = TrackEntity(
            signature = data.signature,
            title = data.title,
            artist = data.artist,
            album = data.album,
            albumArtist = data.albumArtist,
            genre = data.genre,
            year = data.year,
            trackNumber = data.trackNumber,
            durationMs = data.durationMs,
            albumArtPath = data.artPath,
            extraMetadataJson = data.extrasJson
        )
        val insertedId = dao.insertTrack(entity)
        return if (insertedId > 0L) insertedId else (dao.findTrackBySignature(data.signature)?.id ?: -1L)
    }

    private fun extractMetadata(
        pkg: String,
        meta: MediaMetadata?,
        sbn: StatusBarNotification?,
        state: PlaybackState?
    ): ExtractedMetadata? {
        val extras = sbn?.notification?.extras

        fun strOrUnknown(vararg candidates: String?): String =
            candidates.firstOrNull { !it.isNullOrBlank() }?.trim() ?: "Unknown"

        val title = strOrUnknown(
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        )
        if (title == "Unknown" && meta == null) return null

        val artist = strOrUnknown(
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR),
            meta?.getString(MediaMetadata.METADATA_KEY_WRITER),
            meta?.getString(MediaMetadata.METADATA_KEY_COMPOSER),
            extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        )

        val album = strOrUnknown(
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        )

        val albumArtist = strOrUnknown(meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
        val genre = strOrUnknown(meta?.getString(MediaMetadata.METADATA_KEY_GENRE))
        val yearLong = meta?.getLong(MediaMetadata.METADATA_KEY_YEAR) ?: 0L
        val year = if (yearLong > 0) yearLong.toString() else strOrUnknown(meta?.getString(MediaMetadata.METADATA_KEY_DATE))
        val trackNo = meta?.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER) ?: 0L
        val trackNumber = if (trackNo > 0) trackNo.toString() else "Unknown"
        val durationMs = (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L)

        val bitmap: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: sbn?.notification?.getLargeIcon()?.loadDrawable(applicationContext)?.let {
                (it as? BitmapDrawable)?.bitmap
            }

        val signature = "${title.lowercase()}|${artist.lowercase()}|${album.lowercase()}"
        val artPath = bitmap?.let { saveBitmapLocally(signature, it) }

        val extraMap = buildMap<String, String> {
            put("sourcePackage", pkg.ifBlank { "Unknown" })
            put("playbackSpeed", (state?.playbackSpeed ?: 1f).toString())
            put("timelinePosMs", (state?.position ?: 0L).toString())
            meta?.keySet()?.forEach { key ->
                runCatching {
                    meta.getString(key)?.let { put(key, it) }
                }
            }
            extras?.collectReadableKeys(this)
        }

        return ExtractedMetadata(
            signature = signature,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            durationMs = durationMs,
            artPath = artPath,
            extrasJson = Json.encodeToString(extraMap)
        )
    }

    private fun Bundle.collectReadableKeys(target: MutableMap<String, String>) {
        keySet()?.forEach { key ->
            @Suppress("DEPRECATION")
            val v = get(key)
            if (v is CharSequence || v is Number || v is Boolean) {
                target["notif_$key"] = v.toString()
            }
        }
    }

    private fun saveBitmapLocally(signature: String, bitmap: Bitmap): String? = runCatching {
        val dir = File(filesDir, "album_art").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val file = File(dir, "$hash.jpg")
        if (!file.exists()) {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        }
        file.absolutePath
    }.getOrNull()

    override fun onDestroy() {
        runBlocking { flushCurrentPlaybackSync() }
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class ExtractedMetadata(
        val signature: String,
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String,
        val genre: String,
        val year: String,
        val trackNumber: String,
        val durationMs: Long,
        val artPath: String?,
        val extrasJson: String
    )
}
