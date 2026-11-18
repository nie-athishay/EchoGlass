package com.echoglass.music.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.echoglass.music.data.backup.BackupManager
import com.echoglass.music.data.db.AppDatabase
import com.echoglass.music.data.db.SessionWithTrack
import com.echoglass.music.data.db.TrackEntity
import com.echoglass.music.domain.ChartBucket
import com.echoglass.music.domain.RankedItem
import com.echoglass.music.domain.StatsCalculator
import com.echoglass.music.domain.formatDuration
import com.echoglass.music.ui.components.AnimatedGlassBarChart
import com.echoglass.music.ui.theme.AccentIndigo
import com.echoglass.music.ui.theme.AccentMint
import com.echoglass.music.ui.theme.AccentRose
import com.echoglass.music.ui.theme.MutedInk
import com.echoglass.music.ui.theme.SoftInk
import com.echoglass.music.ui.theme.TranslucentWhiteScaffoldBackground
import com.echoglass.music.ui.theme.glassSurface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

enum class StatsPeriod { TODAY, WEEK, MONTH, YEAR }

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).musicDao()
    private val backupManager = BackupManager(app, dao)

    val sessions: StateFlow<List<SessionWithTrack>> = dao.observeAllSessionsWithTrack()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracks: StateFlow<List<TrackEntity>> = dao.observeAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    private val _selectedYear = MutableStateFlow(LocalDate.now().year)

    private val _statusMessage = MutableSharedFlow<String>()
    val statusMessage: SharedFlow<String> = _statusMessage

    val todayStats = sessions.map { StatsCalculator.computeToday(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsCalculator.computeToday(emptyList()))

    val weekStats = sessions.map { StatsCalculator.computeWeek(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsCalculator.computeWeek(emptyList()))

    val monthStats = combine(sessions, _selectedMonth) { list, m ->
        StatsCalculator.computeMonth(list, m)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        StatsCalculator.computeMonth(emptyList(), YearMonth.now())
    )

    val yearStats = combine(sessions, _selectedYear) { list, y ->
        StatsCalculator.computeYear(list, y)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        StatsCalculator.computeYear(emptyList(), LocalDate.now().year)
    )

    fun shiftMonth(delta: Long) {
        _selectedMonth.value = _selectedMonth.value.plusMonths(delta)
    }

    fun shiftYear(delta: Int) {
        _selectedYear.value = _selectedYear.value + delta
    }

    fun mergeTracks(sourceId: Long, targetId: Long) {
        viewModelScope.launch {
            dao.mergeTracks(sourceId, targetId)
            _statusMessage.emit("Tracks merged successfully")
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            backupManager.exportToUri(uri)
                .onSuccess { count -> _statusMessage.emit("Exported $count listening sessions") }
                .onFailure { _statusMessage.emit("Export failed: ${it.localizedMessage}") }
        }
    }

    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            backupManager.restoreFromUri(uri)
                .onSuccess { count -> _statusMessage.emit("Restored $count listening sessions") }
                .onFailure { _statusMessage.emit("Restore failed: ${it.localizedMessage}") }
        }
    }
}

@Composable
fun EchoGlassRoot(viewModel: MusicViewModel) {
    var currentTab by remember { mutableIntStateOf(0) } // 0=Stats, 1=History, 2=Listener, 3=Export
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.statusMessage.collect { snackbarHostState.showSnackbar(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportBackup) }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::restoreBackup) }

    TranslucentWhiteScaffoldBackground {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassSurface(28.dp)
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavigationBarItem(
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            icon = { Icon(Icons.Rounded.Equalizer, contentDescription = "Stats") },
                            label = { Text("Stats", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentIndigo,
                                selectedTextColor = AccentIndigo,
                                indicatorColor = AccentIndigo.copy(alpha = 0.12f)
                            )
                        )
                        NavigationBarItem(
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            icon = { Icon(Icons.Rounded.History, contentDescription = "History") },
                            label = { Text("History", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentIndigo,
                                selectedTextColor = AccentIndigo,
                                indicatorColor = AccentIndigo.copy(alpha = 0.12f)
                            )
                        )
                        NavigationBarItem(
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            icon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = "Listener") },
                            label = { Text("Listener", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentIndigo,
                                selectedTextColor = AccentIndigo,
                                indicatorColor = AccentIndigo.copy(alpha = 0.12f)
                            )
                        )
                        NavigationBarItem(
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = "Export") },
                            label = { Text("Export", maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AccentIndigo,
                                selectedTextColor = AccentIndigo,
                                indicatorColor = AccentIndigo.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("EchoGlass", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SoftInk)
                }

                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        (fadeIn(tween(320)) + slideInVertically { it / 16 })
                            .togetherWith(fadeOut(tween(220)))
                    },
                    label = "rootTabTransition"
                ) { tab ->
                    when (tab) {
                        0 -> StatsDashboardScreen(viewModel)
                        1 -> HistoryAndMergeScreen(viewModel)
                        2 -> ListenerSettingsScreen()
                        else -> ExportRestoreScreen(
                            viewModel = viewModel,
                            onExportClick = {
                                exportLauncher.launch("echoglass_backup_${LocalDate.now()}.json")
                            },
                            onRestoreClick = {
                                restoreLauncher.launch(arrayOf("application/json"))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ListenerSettingsScreen() {
    val context = LocalContext.current
    val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
    val isEnabled = context.packageName in enabledPackages

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassStatCard(
            title = "Android Notification Listener",
            headline = if (isEnabled) "Active" else "Permission Needed"
        ) {
            Text(
                text = if (isEnabled) {
                    "EchoGlass is capturing song titles, artists, albums, album art, timelines, and extras automatically."
                } else {
                    "Grant Notification Access so EchoGlass can track playback from Spotify, YouTube Music, Apple Music, and local players."
                },
                color = MutedInk,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
            ) {
                Text("Open Notification Access Settings")
            }
        }
    }
}

@Composable
fun ExportRestoreScreen(
    viewModel: MusicViewModel,
    onExportClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val tracks by viewModel.tracks.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassStatCard("Backup & Export", "${sessions.size} Sessions") {
            Text("${tracks.size} unique tracks in local history", color = MutedInk, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onExportClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
            ) {
                Icon(Icons.Rounded.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export History Backup (.json)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRestoreClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Restore from Backup File")
            }
        }
    }
}

@Composable
fun StatsDashboardScreen(viewModel: MusicViewModel) {
    var period by remember { mutableStateOf(StatsPeriod.TODAY) }
    val today by viewModel.todayStats.collectAsState()
    val week by viewModel.weekStats.collectAsState()
    val month by viewModel.monthStats.collectAsState()
    val year by viewModel.yearStats.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(18.dp)
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatsPeriod.entries.forEach { item ->
                val selected = period == item
                FilterChip(
                    selected = selected,
                    onClick = { period = item },
                    label = { Text(item.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    border = null
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        AnimatedContent(
            targetState = period,
            transitionSpec = {
                (fadeIn(tween(300)) + scaleIn(initialScale = 0.97f))
                    .togetherWith(fadeOut(tween(200)))
            },
            label = "statsPeriodTransition"
        ) { activePeriod ->
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                when (activePeriod) {
                    StatsPeriod.TODAY -> {
                        item {
                            GlassStatCard("Today's Listening", formatDuration(today.totalListeningMs)) {
                                val sign = if (today.deltaFromYesterdayMs >= 0) "+" else "-"
                                val deltaText = "$sign${formatDuration(abs(today.deltaFromYesterdayMs))}"
                                val pctText = today.deltaFromYesterdayPercent?.let {
                                    " (${if (it >= 0) "+" else ""}${"%.1f".format(it)}%)"
                                } ?: " (New today)"
                                Text(
                                    text = "vs Yesterday: $deltaText$pctText",
                                    color = if (today.deltaFromYesterdayMs >= 0) AccentMint else AccentRose,
                                    fontWeight = FontWeight.Medium
                                )
                                Text("Peak listening window: ${today.peakHourRange}", color = MutedInk)
                            }
                        }
                        item { GlassChartCard("Hourly Activity", today.hourlyBuckets) }
                    }

                    StatsPeriod.WEEK -> {
                        item {
                            GlassStatCard("This Week", formatDuration(week.totalListeningMs)) {
                                Text("Daily Average: ${formatDuration(week.avgDailyMs)}  •  Peak Day: ${week.peakDayLabel}", color = MutedInk)
                                Text("${week.totalArtists} Artists  •  ${week.totalSongs} Songs", color = MutedInk)
                            }
                        }
                        item { GlassChartCard("Day-wise Listening", week.dayBuckets) }
                        item {
                            GlassRankedSection(
                                "Top #1 Highlights",
                                listOfNotNull(
                                    week.topArtist?.let { it.copy(subtitle = "Top Artist • ${it.subtitle}") },
                                    week.topSong?.let { it.copy(subtitle = "Top Song • ${it.subtitle}") },
                                    week.topAlbum?.let { it.copy(subtitle = "Top Album • ${it.subtitle}") }
                                )
                            )
                        }
                    }

                    StatsPeriod.MONTH -> {
                        item {
                            PeriodSelectorRow(
                                label = month.month.toString(),
                                onPrev = { viewModel.shiftMonth(-1) },
                                onNext = { viewModel.shiftMonth(1) }
                            )
                        }
                        if (!month.isRevealed) {
                            item {
                                LockedRevealCard(
                                    title = "${month.month} Recap Locked",
                                    message = "Monthly stats are revealed after the month ends on ${month.unlockDateLabel}."
                                )
                            }
                        } else {
                            item {
                                GlassStatCard("${month.month} Total", formatDuration(month.totalListeningMs)) {
                                    Text("${month.totalArtists} Artists  •  ${month.totalSongs} Songs", color = MutedInk)
                                }
                            }
                            item { GlassChartCard("Week-wise Listening", month.weekBuckets) }
                            item { GlassRankedSection("Top 5 Songs", month.top5Songs) }
                            item { GlassRankedSection("Top 5 Artists", month.top5Artists) }
                            item { GlassRankedSection("Top 5 Albums", month.top5Albums) }
                        }
                    }

                    StatsPeriod.YEAR -> {
                        item {
                            PeriodSelectorRow(
                                label = year.year.toString(),
                                onPrev = { viewModel.shiftYear(-1) },
                                onNext = { viewModel.shiftYear(1) }
                            )
                        }
                        if (!year.isRevealed) {
                            item {
                                LockedRevealCard(
                                    title = "${year.year} Wrapped Locked",
                                    message = "Yearly stats are revealed at the end of the year on ${year.unlockDateLabel}."
                                )
                            }
                        } else {
                            item {
                                GlassStatCard("${year.year} Total", formatDuration(year.totalListeningMs)) {
                                    Text("Daily Avg: ${formatDuration(year.avgDailyMs)}  •  Peak Day: ${year.peakDayLabel}", color = MutedInk)
                                    Text("${year.totalArtists} Artists  •  ${year.totalSongs} Songs", color = MutedInk)
                                }
                            }
                            item { GlassChartCard("Month-wise Listening", year.monthBuckets) }
                            item { GlassRankedSection("Top 5 Songs", year.top5Songs) }
                            item { GlassRankedSection("Top 5 Artists", year.top5Artists) }
                            item { GlassRankedSection("Top 5 Albums", year.top5Albums) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryAndMergeScreen(viewModel: MusicViewModel) {
    val sessions by viewModel.sessions.collectAsState()
    var mergeSource by remember { mutableStateOf<TrackEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = mergeSource != null) {
            mergeSource?.let { src ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .glassSurface(18.dp)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Tap any target track below to merge \"${src.title}\" into it",
                        modifier = Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = AccentIndigo
                    )
                    TextButton(onClick = { mergeSource = null }) { Text("Cancel") }
                }
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(sessions, key = { it.session.id }) { item ->
                val track = item.track
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassSurface(20.dp)
                        .clickable {
                            val src = mergeSource
                            if (src != null && src.id != track.id) {
                                viewModel.mergeTracks(src.id, track.id)
                                mergeSource = null
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track.albumArtPath,
                        contentDescription = null,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.SemiBold, color = SoftInk)
                        Text("${track.artist} • ${track.album}", fontSize = 12.sp, color = MutedInk)
                        Text(
                            "Listened: ${formatDuration(item.session.listenedDurationMs)} • Timeline: ${formatDuration(item.session.lastTimelinePosMs)}",
                            fontSize = 11.sp,
                            color = MutedInk
                        )
                    }
                    IconButton(onClick = { mergeSource = track }) {
                        Icon(Icons.Rounded.MergeType, "Merge Track", tint = AccentIndigo)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassStatCard(title: String, headline: String, body: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, fontSize = 13.sp, color = MutedInk)
        Text(headline, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = SoftInk)
        Spacer(Modifier.height(4.dp))
        body()
    }
}

@Composable
private fun GlassChartCard(title: String, buckets: List<ChartBucket>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(18.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = SoftInk)
        Spacer(Modifier.height(12.dp))
        AnimatedGlassBarChart(buckets)
    }
}

@Composable
private fun GlassRankedSection(title: String, items: List<RankedItem>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, color = SoftInk)
        if (items.isEmpty()) {
            Text("No data yet", color = MutedInk, fontSize = 13.sp)
        } else {
            items.forEachIndexed { idx, item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = AccentIndigo, modifier = Modifier.width(28.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.Medium, color = SoftInk)
                        Text(item.subtitle, fontSize = 12.sp, color = MutedInk)
                    }
                    Text(formatDuration(item.totalMs), fontWeight = FontWeight.SemiBold, color = SoftInk)
                }
            }
        }
    }
}

@Composable
private fun LockedRevealCard(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Rounded.Lock, null, tint = AccentIndigo, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = SoftInk)
        Spacer(Modifier.height(4.dp))
        Text(message, color = MutedInk, fontSize = 13.sp)
    }
}

@Composable
private fun PeriodSelectorRow(label: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(18.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, "Previous") }
        Text(label, fontWeight = FontWeight.SemiBold, color = SoftInk)
        IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "Next") }
    }
}
