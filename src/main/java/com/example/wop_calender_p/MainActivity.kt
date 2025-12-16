package com.example.wop_calender_p

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import com.example.wop_calender_p.ui.theme.ADPTheme
import com.google.ai.client.generativeai.GenerativeModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// ==========================================
// [1] Room Database & Entity
// ==========================================
@Entity(tableName = "events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val start: String,
    val end: String,
    val color: Int,
    val location: String? = null,
    val placeStatus: String? = null,
    val aiAdvice: String? = null
)

@Dao
interface EventDao {
    @Query("SELECT * FROM events")
    fun getAllEvents(): Flow<List<CalendarEventEntity>>

    @Insert
    suspend fun insertEvent(event: CalendarEventEntity)

    @Delete
    suspend fun deleteEvent(event: CalendarEventEntity)
}

@Database(entities = [CalendarEventEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
}

// ==========================================
// [2] Hilt Module
// ==========================================
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "calendar_db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideEventDao(database: AppDatabase): EventDao = database.eventDao()

    @Provides
    @Singleton
    fun provideGenerativeModel(): GenerativeModel {
        // ⭐ local.properties에서 BuildConfig를 통해 API 키를 가져옴
        return GenerativeModel(
            modelName = "gemini-1.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }
}

// ==========================================
// [3] ViewModel
// ==========================================
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val dao: EventDao,
    private val generativeModel: GenerativeModel
) : ViewModel() {

    val events: Flow<List<Event>> = dao.getAllEvents().map { entities ->
        entities.map { entity ->
            Event(
                id = entity.id,
                title = entity.title,
                start = LocalDateTime.parse(entity.start),
                end = LocalDateTime.parse(entity.end),
                color = Color(entity.color),
                location = entity.location,
                placeStatus = entity.placeStatus,
                aiAdvice = entity.aiAdvice
            )
        }
    }

    fun addEvent(event: Event) {
        viewModelScope.launch {
            dao.insertEvent(
                CalendarEventEntity(
                    title = event.title,
                    start = event.start.toString(),
                    end = event.end.toString(),
                    color = event.color.toArgb(),
                    location = event.location,
                    placeStatus = event.placeStatus,
                    aiAdvice = event.aiAdvice
                )
            )
        }
    }

    // ⭐ Gemini AI 조언 요청 함수
    suspend fun askGemini(title: String, location: String?): String {
        return try {
            val locationText = if (location.isNullOrBlank()) "미정" else location
            val prompt = """
                나는 '$title'이라는 일정을 '$locationText'에서 진행할 예정이야.
                이 일정을 위한 준비물이나 주의할 점을 한국어로 아주 짧게 1~2문장으로 조언해줘.
                친근하고 실용적인 조언을 부탁해. (존댓말 사용)
            """.trimIndent()

            val response = generativeModel.generateContent(prompt)
            response.text ?: "AI가 조언을 준비 중입니다..."
        } catch (e: Exception) {
            when {
                e.message?.contains("API key") == true ->
                    "❌ API 키 오류: local.properties의 GEMINI_API_KEY를 확인해주세요."
                e.message?.contains("network") == true || e.message?.contains("Unable to resolve host") == true ->
                    "❌ 네트워크 오류: 인터넷 연결을 확인해주세요."
                e.message?.contains("quota") == true ->
                    "❌ API 할당량 초과: 잠시 후 다시 시도해주세요."
                else ->
                    "❌ AI 연결 실패: ${e.localizedMessage ?: "알 수 없는 오류"}"
            }
        }
    }
}

// ==========================================
// [4] UI Constants & Data Class
// ==========================================
private val HOUR_HEIGHT = 64.dp
private val TIME_COLUMN_WIDTH = 60.dp
private val GridLineColor = Color(0xFFEEEEEE)
private val CurrentTimeColor = Color(0xFFFF5252)
private val PurpleSelected = Color(0xFF5E5CE6)

private val yearMonthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val dayFormatter = DateTimeFormatter.ofPattern("d")
private val dialogDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val dialogTimeFormatter = DateTimeFormatter.ofPattern("a hh:mm")
private val DAYS_OF_WEEK = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")

private val EVENT_COLORS = listOf(
    Color(0xFF7986CB), Color(0xFF4DB6AC), Color(0xFFE57373), Color(0xFF9575CD),
    Color(0xFFF06292), Color(0xFFFF8A65), Color(0xFFAED581), Color(0xFF4DD0E1)
)

data class Event(
    val id: Long,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val color: Color,
    val location: String? = null,
    val placeStatus: String? = null,
    val aiAdvice: String? = null
)

open class EventLayout(
    val event: Event,
    val top: Dp,
    val height: Dp,
    var left: Float,
    var width: Float
)

// ==========================================
// [5] Utility Functions
// ==========================================
private fun getStartOfWeek(date: LocalDate): LocalDate {
    val weekFields = WeekFields.of(Locale.US)
    return date.with(weekFields.dayOfWeek(), 1)
}

private fun Dp.toLocalDateTime(baseDate: LocalDate): LocalDateTime {
    return baseDate.atStartOfDay().plusMinutes((this / HOUR_HEIGHT * 60).toLong())
}

private fun Long.toCalendarDp(): Dp {
    return (this / 60f).toFloat().dp * HOUR_HEIGHT.value
}

// ==========================================
// [6] MainActivity
// ==========================================
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ADPTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    WeeklyCalendarScreen()
                }
            }
        }
    }
}

// ==========================================
// [7] Main Calendar Screen
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyCalendarScreen(viewModel: CalendarViewModel = hiltViewModel()) {
    var currentWeekStart by remember { mutableStateOf(getStartOfWeek(LocalDate.now())) }
    val events by viewModel.events.collectAsState(initial = emptyList())
    var showAddEventDialog by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(modifier = Modifier.fillMaxSize()) {
        WeeklyHeader(
            currentWeekStart = currentWeekStart,
            onPrevClick = { currentWeekStart = currentWeekStart.minusWeeks(1) },
            onNextClick = { currentWeekStart = currentWeekStart.plusWeeks(1) },
            onTodayClick = { currentWeekStart = getStartOfWeek(LocalDate.now()) },
            onNewEventClick = { showAddEventDialog = true }
        )

        DayHeaders(startOfWeek = currentWeekStart)

        HorizontalDivider(thickness = 1.dp, color = GridLineColor)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
        ) {
            TimeSidebar()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(HOUR_HEIGHT * 24)
            ) {
                CalendarGrid(
                    startOfWeek = currentWeekStart,
                    onDragStart = { _, _ -> },
                    onDrag = { _, _ -> },
                    onDragEnd = { }
                )
                EventRenderer(
                    startOfWeek = currentWeekStart,
                    events = events,
                    onEventClick = { }
                )
                CurrentTimeLine(startOfWeek = currentWeekStart)
            }
        }
    }

    if (showAddEventDialog) {
        AddEventDialog(
            onDismiss = { showAddEventDialog = false },
            onSave = {
                viewModel.addEvent(it)
                showAddEventDialog = false
            },
            viewModel = viewModel
        )
    }
}

// ==========================================
// [8] Weekly Header
// ==========================================
@Composable
fun WeeklyHeader(
    currentWeekStart: LocalDate,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit,
    onTodayClick: () -> Unit,
    onNewEventClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = currentWeekStart.format(yearMonthFormatter),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF1A1A1A)
            )
            Text(
                text = "WEEKLY SCHEDULE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                color = Color.White
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPrevClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .width(1.dp),
                        color = Color(0xFFE0E0E0)
                    )

                    TextButton(
                        onClick = onTodayClick,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(
                            "Today",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF333333)
                        )
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp)
                            .width(1.dp),
                        color = Color(0xFFE0E0E0)
                    )

                    IconButton(
                        onClick = onNextClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }
                }
            }

            Button(
                onClick = onNewEventClick,
                colors = ButtonDefaults.buttonColors(containerColor = PurpleSelected),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "New Event",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ==========================================
// [9] Day Headers
// ==========================================
@Composable
fun DayHeaders(startOfWeek: LocalDate) {
    val today = LocalDate.now()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Spacer(modifier = Modifier.width(TIME_COLUMN_WIDTH))

        for (i in 0..6) {
            val date = startOfWeek.plusDays(i.toLong())
            val isToday = date == today

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = DAYS_OF_WEEK[i],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) PurpleSelected else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = date.format(dayFormatter),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) PurpleSelected else Color.Black
                )
            }
        }
    }
}

// ==========================================
// [10] Time Sidebar
// ==========================================
@Composable
fun TimeSidebar() {
    Column(
        modifier = Modifier.width(TIME_COLUMN_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (i in 0..23) {
            Box(
                modifier = Modifier
                    .height(HOUR_HEIGHT)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (i > 0) {
                    val timeText = when {
                        i == 12 -> "12 PM"
                        i > 12 -> "${i - 12} PM"
                        else -> "$i AM"
                    }
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        modifier = Modifier.offset(y = (-8).dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// [11] Calendar Grid
// ==========================================
@Composable
fun CalendarGrid(
    startOfWeek: LocalDate,
    onDragStart: (LocalDate, LocalDateTime) -> Unit,
    onDrag: (LocalDate, LocalDateTime) -> Unit,
    onDragEnd: () -> Unit
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(startOfWeek) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val dayWidth = size.width / 7f
                        val dayIndex = (offset.x / dayWidth).toInt().coerceIn(0, 6)
                        val dayDate = startOfWeek.plusDays(dayIndex.toLong())
                        val time = offset.y.toDp().toLocalDateTime(dayDate)
                        onDragStart(dayDate, time)
                    },
                    onDrag = { change, _ ->
                        val dayWidth = size.width / 7f
                        val dayIndex = (change.position.x / dayWidth).toInt().coerceIn(0, 6)
                        val dayDate = startOfWeek.plusDays(dayIndex.toLong())
                        val time = change.position.y.toDp().toLocalDateTime(dayDate)
                        onDrag(dayDate, time)
                    },
                    onDragEnd = { onDragEnd() }
                )
            }
    ) {
        val dayWidth = size.width / 7f
        val hourHeight = HOUR_HEIGHT.toPx()

        for (i in 1..23) {
            drawLine(
                color = GridLineColor,
                start = Offset(0f, i * hourHeight),
                end = Offset(size.width, i * hourHeight),
                strokeWidth = 1.dp.toPx()
            )
        }

        for (i in 1..6) {
            drawLine(
                color = GridLineColor,
                start = Offset(i * dayWidth, 0f),
                end = Offset(i * dayWidth, size.height),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

// ==========================================
// [12] Current Time Line
// ==========================================
@Composable
fun CurrentTimeLine(startOfWeek: LocalDate) {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val endOfWeek = startOfWeek.plusDays(7)

    if (!today.isBefore(startOfWeek) && today.isBefore(endOfWeek)) {
        val dayIndex = ChronoUnit.DAYS.between(startOfWeek, today).toInt()
        val timeOffset = (now.hour * 60L + now.minute).toCalendarDp()

        Canvas(modifier = Modifier.fillMaxSize()) {
            val dayWidth = size.width / 7f
            val y = timeOffset.toPx()
            val xStart = dayIndex * dayWidth
            val xEnd = xStart + dayWidth

            drawCircle(
                color = CurrentTimeColor,
                radius = 5.dp.toPx(),
                center = Offset(xStart, y)
            )
            drawLine(
                color = CurrentTimeColor,
                start = Offset(xStart, y),
                end = Offset(xEnd, y),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

// ==========================================
// [13] Event Renderer
// ==========================================
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun EventRenderer(
    startOfWeek: LocalDate,
    events: List<Event>,
    onEventClick: (Event) -> Unit
) {
    val endOfWeek = startOfWeek.plusDays(7)
    val weekEvents = events.filter {
        !it.start.isBefore(startOfWeek.atStartOfDay()) &&
                it.start.isBefore(endOfWeek.atStartOfDay())
    }

    val eventsByDay = remember(weekEvents, startOfWeek) {
        val groups: Array<MutableList<Event>> = Array(7) { mutableListOf() }
        weekEvents.forEach { event ->
            val dayIndex = ChronoUnit.DAYS.between(startOfWeek, event.start.toLocalDate()).toInt()
            if (dayIndex in 0..6) {
                groups[dayIndex].add(event)
            }
        }
        groups.map { group -> group.sortedBy { event -> event.start } }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dayWidth = maxWidth / 7

        eventsByDay.forEachIndexed { dayIndex, dayEvents ->
            if (dayEvents.isNotEmpty()) {
                val layouts = calculateEventLayouts(dayEvents)
                layouts.forEach { layout ->
                    EventBlock(
                        event = layout.event,
                        layout = layout,
                        onClick = { onEventClick(layout.event) },
                        modifier = Modifier
                            .absoluteOffset(
                                x = dayWidth * dayIndex + (dayWidth * layout.left),
                                y = layout.top
                            )
                            .width(dayWidth * layout.width - 2.dp)
                            .height(layout.height)
                            .zIndex(if (layout.event.id == 0L) 10f else 1f)
                    )
                }
            }
        }
    }
}

// ==========================================
// [14] Event Block
// ==========================================
@Composable
private fun EventBlock(
    event: Event,
    layout: EventLayout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(event.color.copy(alpha = 0.9f))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .clickable {
                if (event.location != null) {
                    val gmmIntentUri = Uri.parse("geo:0,0?q=${event.location}")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    try {
                        context.startActivity(mapIntent)
                    } catch (e: Exception) {
                        onClick()
                    }
                } else {
                    onClick()
                }
            }
            .padding(4.dp)
    ) {
        Column {
            Text(
                text = event.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (event.aiAdvice != null) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "AI 조언 있음",
                    tint = Color.Yellow,
                    modifier = Modifier.size(12.dp)
                )
            }

            if (event.location != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 8.sp,
                        color = Color.White.copy(alpha = 0.95f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (event.placeStatus != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        color = if (event.placeStatus == "영업 중") Color(0xFF66BB6A)
                        else Color(0xFFEF5350),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = event.placeStatus,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            } else if (layout.height > 30.dp) {
                Text(
                    text = event.start.format(DateTimeFormatter.ofPattern("H:mm")),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }
        }
    }
}

// ==========================================
// [15] Event Layout Calculator
// ==========================================
private class MutableEventLayout(
    event: Event,
    top: Dp,
    height: Dp,
    val colIndex: Int
) : EventLayout(event, top, height, 0f, 0f) {
    fun toEventLayout(): EventLayout = EventLayout(event, top, height, left, width)
}

private fun calculateEventLayouts(dayEvents: List<Event>): List<EventLayout> {
    val layouts = mutableListOf<MutableEventLayout>()

    for (event in dayEvents) {
        val startMinutes = event.start.hour * 60L + event.start.minute
        val endMinutes = event.end.hour * 60L + event.end.minute
        val top = startMinutes.toCalendarDp()
        val height = (endMinutes - startMinutes).toCalendarDp()

        val collidingEvents = layouts.filter {
            val lStart = it.event.start.hour * 60L + it.event.start.minute
            val lEnd = it.event.end.hour * 60L + it.event.end.minute
            (startMinutes < lEnd && endMinutes > lStart)
        }

        val occupiedColumns = collidingEvents.map { it.colIndex }.toSet()
        var colIndex = 0
        while (occupiedColumns.contains(colIndex)) colIndex++

        layouts.add(MutableEventLayout(event, top, height, colIndex))
    }

    val finalLayouts = mutableListOf<EventLayout>()
    val processedLayouts = mutableSetOf<MutableEventLayout>()

    layouts.forEach { layout ->
        if (processedLayouts.contains(layout)) return@forEach

        val overlapGroup = mutableSetOf<MutableEventLayout>()
        val queue = ArrayDeque<MutableEventLayout>()
        overlapGroup.add(layout)
        queue.add(layout)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            layouts.forEach { other ->
                if (!overlapGroup.contains(other)) {
                    val cStart = current.event.start.toLocalTime()
                    val cEnd = current.event.end.toLocalTime()
                    val oStart = other.event.start.toLocalTime()
                    val oEnd = other.event.end.toLocalTime()

                    if (cStart.isBefore(oEnd) && cEnd.isAfter(oStart)) {
                        overlapGroup.add(other)
                        queue.add(other)
                    }
                }
            }
        }

        processedLayouts.addAll(overlapGroup)

        val maxCols = (overlapGroup.maxOfOrNull { it.colIndex } ?: 0) + 1
        val colWidth = 1.0f / maxCols

        overlapGroup.forEach { l ->
            l.left = l.colIndex * colWidth
            l.width = colWidth
            finalLayouts.add(l.toEventLayout())
        }
    }

    return finalLayouts
}

// ==========================================
// [16] Add Event Dialog (⭐ Gemini 통합)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventDialog(
    onDismiss: () -> Unit,
    onSave: (Event) -> Unit,
    viewModel: CalendarViewModel
) {
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var placeStatus by remember { mutableStateOf<String?>(null) }
    var aiAdvice by remember { mutableStateOf<String?>(null) }
    var isAiLoading by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var startTime by remember {
        mutableStateOf<LocalTime?>(LocalTime.now().truncatedTo(ChronoUnit.HOURS))
    }
    var endTime by remember {
        mutableStateOf<LocalTime?>(LocalTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1))
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val datePickerState = rememberDatePickerState()
    val startTimePickerState = rememberTimePickerState(
        initialHour = startTime?.hour ?: 9,
        initialMinute = 0
    )
    val endTimePickerState = rememberTimePickerState(
        initialHour = endTime?.hour ?: 10,
        initialMinute = 0
    )

    val isRiskyStatus = placeStatus?.contains("브레이크") == true ||
            placeStatus?.contains("종료") == true
    val isSaveEnabled = title.isNotBlank() &&
            date != null &&
            startTime != null &&
            endTime != null &&
            (endTime?.isAfter(startTime) ?: false)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "새 일정 만들기",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = Color.Gray
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // 일정 제목
                    Text(
                        "일정 제목",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = { Text("예: 팀 회의, 점심 약속") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedBorderColor = PurpleSelected
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 장소
                    Text(
                        "장소",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = location,
                        onValueChange = { location = it },
                        placeholder = { Text("장소 입력 (선택사항)") },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color(0xFFE0E0E0),
                            focusedBorderColor = PurpleSelected
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 장소 추천 칩
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SuggestionChip(
                            onClick = {
                                location = "스타벅스"
                                placeStatus = "영업 중"
                            },
                            label = { Text("스타벅스 (✅ 영업 중)") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFFE8F5E9),
                                labelColor = Color(0xFF2E7D32)
                            ),
                            border = null
                        )
                        SuggestionChip(
                            onClick = {
                                location = "맛집"
                                placeStatus = "브레이크 타임"
                            },
                            label = { Text("맛집 (⚠️ 브레이크)") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFFFFF3E0),
                                labelColor = Color(0xFFEF6C00)
                            ),
                            border = null
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ⭐ Gemini AI 조언 버튼
                    OutlinedButton(
                        onClick = {
                            if (title.isNotBlank()) {
                                isAiLoading = true
                                aiAdvice = null
                                scope.launch {
                                    val advice = viewModel.askGemini(title, location)
                                    aiAdvice = advice
                                    isAiLoading = false
                                }
                            }
                        },
                        enabled = title.isNotBlank() && !isAiLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (title.isNotBlank()) PurpleSelected.copy(alpha = 0.5f)
                            else Color.Gray.copy(alpha = 0.3f)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = PurpleSelected,
                            disabledContentColor = Color.Gray
                        )
                    ) {
                        if (isAiLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = PurpleSelected
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("AI가 분석 중...")
                        } else {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (title.isBlank()) "제목을 먼저 입력하세요"
                                else "✨ Gemini AI 조언 받기"
                            )
                        }
                    }

                    // ⭐ AI 조언 결과 표시
                    if (aiAdvice != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (aiAdvice!!.startsWith("❌"))
                                    Color(0xFFFFEBEE)
                                else
                                    Color(0xFFF3E5F5)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    if (aiAdvice!!.startsWith("❌")) Icons.Default.Warning
                                    else Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (aiAdvice!!.startsWith("❌")) Color(0xFFE53935)
                                    else Color(0xFF8E24AA),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = if (aiAdvice!!.startsWith("❌")) "오류" else "AI 조언",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (aiAdvice!!.startsWith("❌")) Color(0xFFC62828)
                                        else Color(0xFF6A1B9A)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = aiAdvice!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (aiAdvice!!.startsWith("❌")) Color(0xFFB71C1C)
                                        else Color(0xFF4A148C),
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 날짜 선택
                    Text(
                        "날짜",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Black)
                    ) {
                        Icon(
                            Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = date?.format(dialogDateFormatter) ?: "날짜 선택",
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 시간 선택
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "시작",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showStartTimePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.Gray
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = startTime?.format(dialogTimeFormatter) ?: "09:00",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "종료",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showEndTimePicker = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.Gray
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = endTime?.format(dialogTimeFormatter) ?: "10:00",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // 위험 상태 경고
                    if (isRiskyStatus) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "주의: '$placeStatus' 시간대입니다.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 저장 버튼
                Button(
                    onClick = {
                        val newEvent = Event(
                            id = 0,
                            title = title,
                            start = LocalDateTime.of(date!!, startTime!!),
                            end = LocalDateTime.of(date!!, endTime!!),
                            color = EVENT_COLORS.random(),
                            location = if (location.isBlank()) null else location,
                            placeStatus = placeStatus,
                            aiAdvice = aiAdvice
                        )
                        onSave(newEvent)
                    },
                    enabled = isSaveEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleSelected)
                ) {
                    Text(
                        "일정 저장",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // Date Picker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("취소")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Time Picker Dialogs
    if (showStartTimePicker) {
        TimePickerDialog(
            onDismiss = { showStartTimePicker = false },
            onConfirm = {
                startTime = LocalTime.of(
                    startTimePickerState.hour,
                    startTimePickerState.minute
                )
                showStartTimePicker = false
            },
            state = startTimePickerState
        )
    }

    if (showEndTimePicker) {
        TimePickerDialog(
            onDismiss = { showEndTimePicker = false },
            onConfirm = {
                endTime = LocalTime.of(
                    endTimePickerState.hour,
                    endTimePickerState.minute
                )
                showEndTimePicker = false
            },
            state = endTimePickerState
        )
    }
}

// ==========================================
// [17] Time Picker Dialog
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    state: TimePickerState
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
        text = {
            TimePicker(state = state)
        }
    )
}

// ==========================================
// [18] Preview
// ==========================================
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ADPTheme {
        // Preview content
    }
}