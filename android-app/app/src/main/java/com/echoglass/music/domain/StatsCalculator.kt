package com.echoglass.music.domain

import com.echoglass.music.data.db.SessionWithTrack
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class RankedItem(
    val name: String,
    val subtitle: String,
    val totalMs: Long,
    val playCount: Int,
    val albumArtPath: String? = null
)

data class ChartBucket(
    val label: String,
    val valueMs: Long
)

data class TodayStats(
    val totalListeningMs: Long,
    val peakHourRange: String,
    val deltaFromYesterdayMs: Long,
    val deltaFromYesterdayPercent: Double?,
    val hourlyBuckets: List<ChartBucket>
)

data class WeekStats(
    val dayBuckets: List<ChartBucket>,
    val avgDailyMs: Long,
    val peakDayLabel: String,
    val totalArtists: Int,
    val totalSongs: Int,
    val totalListeningMs: Long,
    val topArtist: RankedItem?,
    val topSong: RankedItem?,
    val topAlbum: RankedItem?
)

data class MonthStats(
    val month: YearMonth,
    val isRevealed: Boolean,
    val unlockDateLabel: String,
    val weekBuckets: List<ChartBucket>,
    val top5Songs: List<RankedItem>,
    val top5Artists: List<RankedItem>,
    val top5Albums: List<RankedItem>,
    val totalArtists: Int,
    val totalSongs: Int,
    val totalListeningMs: Long
)

data class YearStats(
    val year: Int,
    val isRevealed: Boolean,
    val unlockDateLabel: String,
    val avgDailyMs: Long,
    val peakDayLabel: String,
    val top5Songs: List<RankedItem>,
    val top5Artists: List<RankedItem>,
    val top5Albums: List<RankedItem>,
    val totalArtists: Int,
    val totalSongs: Int,
    val totalListeningMs: Long,
    val monthBuckets: List<ChartBucket>
)

object StatsCalculator {

    private val zone: ZoneId = ZoneId.systemDefault()

    fun computeToday(all: List<SessionWithTrack>, now: LocalDate = LocalDate.now()): TodayStats {
        val todayStart = now.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = now.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterdayStart = now.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val todaySessions = all.filter { it.session.startedAt in todayStart until tomorrowStart }
        val yesterdaySessions = all.filter { it.session.startedAt in yesterdayStart until todayStart }

        val todayTotal = todaySessions.sumOf { it.session.listenedDurationMs }
        val yesterdayTotal = yesterdaySessions.sumOf { it.session.listenedDurationMs }

        val hourlySums = LongArray(24)
        todaySessions.forEach { s ->
            val hour = Instant.ofEpochMilli(s.session.startedAt).atZone(zone).hour
            hourlySums[hour] += s.session.listenedDurationMs
        }

        val peakHour = hourlySums.indices.maxByOrNull { hourlySums[it] } ?: 0
        val peakHourRange = if (todayTotal == 0L) {
            "No plays yet"
        } else {
            "${formatHour(peakHour)} – ${formatHour((peakHour + 1) % 24)}"
        }

        val deltaMs = todayTotal - yesterdayTotal
        val deltaPct = when {
            yesterdayTotal == 0L && todayTotal == 0L -> 0.0
            yesterdayTotal == 0L -> null
            else -> (deltaMs.toDouble() / yesterdayTotal.toDouble()) * 100.0
        }

        val buckets = (0..23).map { h ->
            ChartBucket(label = if (h % 4 == 0) "${h}h" else "", valueMs = hourlySums[h])
        }

        return TodayStats(
            totalListeningMs = todayTotal,
            peakHourRange = peakHourRange,
            deltaFromYesterdayMs = deltaMs,
            deltaFromYesterdayPercent = deltaPct,
            hourlyBuckets = buckets
        )
    }

    fun computeWeek(all: List<SessionWithTrack>, now: LocalDate = LocalDate.now()): WeekStats {
        val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val sundayEnd = monday.plusDays(7)
        val startMs = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = sundayEnd.atStartOfDay(zone).toInstant().toEpochMilli()

        val weekSessions = all.filter { it.session.startedAt in startMs until endMs }
        val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val dayTotals = LongArray(7)

        weekSessions.forEach { s ->
            val d = Instant.ofEpochMilli(s.session.startedAt).atZone(zone).toLocalDate()
            val idx = d.dayOfWeek.value - 1
            if (idx in 0..6) dayTotals[idx] += s.session.listenedDurationMs
        }

        val totalMs = weekSessions.sumOf { it.session.listenedDurationMs }
        val elapsedDaysInWeek = now.dayOfWeek.value.coerceIn(1, 7)
        val avgDailyMs = totalMs / elapsedDaysInWeek
        val peakIdx = dayTotals.indices.maxByOrNull { dayTotals[it] } ?: 0
        val peakDayLabel = if (totalMs == 0L) "No plays yet" else dayLabels[peakIdx]

        return WeekStats(
            dayBuckets = dayLabels.mapIndexed { idx, lbl -> ChartBucket(lbl, dayTotals[idx]) },
            avgDailyMs = avgDailyMs,
            peakDayLabel = peakDayLabel,
            totalArtists = countUniqueArtists(weekSessions),
            totalSongs = weekSessions.map { it.track.id }.distinct().size,
            totalListeningMs = totalMs,
            topArtist = topArtists(weekSessions, 1).firstOrNull(),
            topSong = topSongs(weekSessions, 1).firstOrNull(),
            topAlbum = topAlbums(weekSessions, 1).firstOrNull()
        )
    }

    fun computeMonth(
        all: List<SessionWithTrack>,
        targetMonth: YearMonth,
        today: LocalDate = LocalDate.now()
    ): MonthStats {
        val isRevealed = today.isAfter(targetMonth.atEndOfMonth())
        val unlockLabel = targetMonth.plusMonths(1).atDay(1).toString()

        if (!isRevealed) {
            return MonthStats(
                month = targetMonth,
                isRevealed = false,
                unlockDateLabel = unlockLabel,
                weekBuckets = emptyList(),
                top5Songs = emptyList(),
                top5Artists = emptyList(),
                top5Albums = emptyList(),
                totalArtists = 0,
                totalSongs = 0,
                totalListeningMs = 0L
            )
        }

        val startMs = targetMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = targetMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = all.filter { it.session.startedAt in startMs until endMs }

        val weekSums = LongArray(5)
        sessions.forEach { s ->
            val dom = Instant.ofEpochMilli(s.session.startedAt).atZone(zone).dayOfMonth
            val weekIdx = ((dom - 1) / 7).coerceIn(0, 4)
            weekSums[weekIdx] += s.session.listenedDurationMs
        }

        return MonthStats(
            month = targetMonth,
            isRevealed = true,
            unlockDateLabel = unlockLabel,
            weekBuckets = weekSums.mapIndexed { i, ms -> ChartBucket("W${i + 1}", ms) },
            top5Songs = topSongs(sessions, 5),
            top5Artists = topArtists(sessions, 5),
            top5Albums = topAlbums(sessions, 5),
            totalArtists = countUniqueArtists(sessions),
            totalSongs = sessions.map { it.track.id }.distinct().size,
            totalListeningMs = sessions.sumOf { it.session.listenedDurationMs }
        )
    }

    fun computeYear(
        all: List<SessionWithTrack>,
        targetYear: Int,
        today: LocalDate = LocalDate.now()
    ): YearStats {
        val isRevealed = today.year > targetYear
        val unlockLabel = "${targetYear + 1}-01-01"

        if (!isRevealed) {
            return YearStats(
                year = targetYear,
                isRevealed = false,
                unlockDateLabel = unlockLabel,
                avgDailyMs = 0L,
                peakDayLabel = "",
                top5Songs = emptyList(),
                top5Artists = emptyList(),
                top5Albums = emptyList(),
                totalArtists = 0,
                totalSongs = 0,
                totalListeningMs = 0L,
                monthBuckets = emptyList()
            )
        }

        val startMs = LocalDate.of(targetYear, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.of(targetYear + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sessions = all.filter { it.session.startedAt in startMs until endMs }

        val totalMs = sessions.sumOf { it.session.listenedDurationMs }
        val daysInYear = if (Year.isLeap(targetYear.toLong())) 366 else 365
        val avgDailyMs = totalMs / daysInYear

        val dayMap = mutableMapOf<LocalDate, Long>()
        val monthSums = LongArray(12)
        sessions.forEach { s ->
            val date = Instant.ofEpochMilli(s.session.startedAt).atZone(zone).toLocalDate()
            dayMap[date] = (dayMap[date] ?: 0L) + s.session.listenedDurationMs
            monthSums[date.monthValue - 1] += s.session.listenedDurationMs
        }

        val peakEntry = dayMap.maxByOrNull { it.value }
        val monthNames = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

        return YearStats(
            year = targetYear,
            isRevealed = true,
            unlockDateLabel = unlockLabel,
            avgDailyMs = avgDailyMs,
            peakDayLabel = peakEntry?.key?.toString() ?: "No plays",
            top5Songs = topSongs(sessions, 5),
            top5Artists = topArtists(sessions, 5),
            top5Albums = topAlbums(sessions, 5),
            totalArtists = countUniqueArtists(sessions),
            totalSongs = sessions.map { it.track.id }.distinct().size,
            totalListeningMs = totalMs,
            monthBuckets = monthNames.mapIndexed { i, m -> ChartBucket(m, monthSums[i]) }
        )
    }

    private fun countUniqueArtists(sessions: List<SessionWithTrack>): Int =
        sessions.map { it.track.artist }.filter { it != "Unknown" }.distinct().size

    private fun topSongs(sessions: List<SessionWithTrack>, limit: Int): List<RankedItem> =
        sessions.groupBy { it.track.id }
            .values
            .map { group ->
                val t = group.first().track
                RankedItem(t.title, t.artist, group.sumOf { it.session.listenedDurationMs }, group.size, t.albumArtPath)
            }
            .sortedByDescending { it.totalMs }
            .take(limit)

    private fun topArtists(sessions: List<SessionWithTrack>, limit: Int): List<RankedItem> =
        sessions.filter { it.track.artist != "Unknown" }
            .groupBy { it.track.artist }
            .map { (artist, group) ->
                RankedItem(artist, "${group.size} plays", group.sumOf { it.session.listenedDurationMs }, group.size)
            }
            .sortedByDescending { it.totalMs }
            .take(limit)

    private fun topAlbums(sessions: List<SessionWithTrack>, limit: Int): List<RankedItem> =
        sessions.filter { it.track.album != "Unknown" }
            .groupBy { it.track.album }
            .map { (album, group) ->
                val t = group.first().track
                RankedItem(album, t.artist, group.sumOf { it.session.listenedDurationMs }, group.size, t.albumArtPath)
            }
            .sortedByDescending { it.totalMs }
            .take(limit)

    private fun formatHour(hour: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        val suffix = if (hour < 12) "AM" else "PM"
        return "$h12 $suffix"
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSec}s"
    }
}
