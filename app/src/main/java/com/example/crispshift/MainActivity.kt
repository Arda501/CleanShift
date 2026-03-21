@file:Suppress("unused", "SpellCheckingInspection", "UNUSED_VARIABLE", "UnusedImport", "ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE", "CanBeVal", "RemoveRedundantQualifierName")

package org.asahin.cleanshift // CHANGE THIS LINE TO YOUR ACTUAL PACKAGE NAME!

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Application
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.io.OutputStreamWriter
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

// --- DATA MODELS ---
enum class RepeatType(val labelRes: Int) {
    NONE(R.string.rep_none), WEEKLY(R.string.rep_weekly), BIWEEKLY(R.string.rep_biweekly), MONTHLY(R.string.rep_monthly), CUSTOM(R.string.rep_custom)
}

data class ShiftRule(
    val id: Int, val name: String, val startDate: LocalDate, val startTime: LocalTime,
    val endTime: LocalTime, val repeatType: RepeatType, val customDays: Int = 0, val hourlyWage: Double, val colorHue: Float? = null
) {
    val calculatedHours: Double get() {
        var minutes = Duration.between(startTime, endTime).toMinutes()
        if (minutes < 0) minutes += 1440
        return minutes / 60.0
    }
}

data class LoggedShift(val date: LocalDate, val shiftName: String, val earned: Double, val hours: Double)

class ShiftViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = application.getSharedPreferences("CrispShiftData", Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(LocalDate::class.java, JsonSerializer<LocalDate> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(LocalDate::class.java, JsonDeserializer { json, _, _ -> LocalDate.parse(json.asString) })
        .registerTypeAdapter(LocalTime::class.java, JsonSerializer<LocalTime> { src, _, _ -> JsonPrimitive(src.toString()) })
        .registerTypeAdapter(LocalTime::class.java, JsonDeserializer { json, _, _ -> LocalTime.parse(json.asString) }).create()

    var currentMonth: YearMonth by mutableStateOf(YearMonth.now())
    var shiftRules: List<ShiftRule> by mutableStateOf(listOf())
    var loggedShifts: Map<LocalDate, LoggedShift> by mutableStateOf(mapOf())
    var themePref: String by mutableStateOf(prefs.getString("theme", "System") ?: "System")
    var useDynamicColor: Boolean by mutableStateOf(prefs.getBoolean("dynamic_color", true))
    var customHue: Float by mutableFloatStateOf(prefs.getFloat("custom_hue", 210f))
    var ledMode: Boolean by mutableStateOf(prefs.getBoolean("led_mode", false))
    var currencyPref: String by mutableStateOf(prefs.getString("currency", "System") ?: "System")
    var autoCompletePref: Boolean by mutableStateOf(prefs.getBoolean("auto_complete", false))
    var languagePref: String by mutableStateOf(prefs.getString("language", "System") ?: "System")

    init { loadData(); runAutoCompleteCheck(); scheduleNextAlarms(application.applicationContext) }

    val monthlyEarned: Double get() = loggedShifts.filterKeys { YearMonth.from(it) == currentMonth }.values.sumOf { it.earned }
    val monthlyHours: Double get() = loggedShifts.filterKeys { YearMonth.from(it) == currentMonth }.values.sumOf { it.hours }
    val monthlyShiftsWorked: Int get() = loggedShifts.filterKeys { YearMonth.from(it) == currentMonth }.size

    val upcomingPlannedShifts: List<ShiftRule> get() {
        val today = LocalDate.now(); val planned = mutableListOf<ShiftRule>()
        for (day in 1..currentMonth.lengthOfMonth()) {
            val date = currentMonth.atDay(day)
            if (!date.isBefore(today) && !loggedShifts.containsKey(date)) {
                getPlannedShiftForDate(date)?.let { planned.add(it) }
            }
        }
        return planned
    }

    val plannedEarnings: Double get() = upcomingPlannedShifts.sumOf { it.calculatedHours * it.hourlyWage }
    val plannedHours: Double get() = upcomingPlannedShifts.sumOf { it.calculatedHours }
    val plannedCount: Int get() = upcomingPlannedShifts.size

    fun formatMoney(amount: Double): String = if (currencyPref == "System") NumberFormat.getCurrencyInstance().format(amount) else "$currencyPref ${String.format(Locale.getDefault(), "%.2f", amount)}"

    fun getPlannedShiftForDate(date: LocalDate): ShiftRule? {
        return shiftRules.find { rule ->
            if (date.isBefore(rule.startDate)) return@find false
            val daysBetween = ChronoUnit.DAYS.between(rule.startDate, date)
            when (rule.repeatType) {
                RepeatType.NONE -> daysBetween == 0L
                RepeatType.WEEKLY -> daysBetween % 7L == 0L
                RepeatType.BIWEEKLY -> daysBetween % 14L == 0L
                RepeatType.MONTHLY -> date.dayOfMonth == rule.startDate.dayOfMonth
                RepeatType.CUSTOM -> rule.customDays > 0 && daysBetween % rule.customDays == 0L
            }
        }
    }

    fun toggleShiftForDate(date: LocalDate) {
        val planned = getPlannedShiftForDate(date)
        val currentLogs = loggedShifts.toMutableMap()
        if (currentLogs.containsKey(date)) currentLogs.remove(date) else if (planned != null) currentLogs[date] = LoggedShift(date, planned.name, planned.calculatedHours * planned.hourlyWage, planned.calculatedHours)
        loggedShifts = currentLogs; saveData(); scheduleNextAlarms(getApplication<Application>().applicationContext)
    }

    fun addShiftRule(rule: ShiftRule) { shiftRules = shiftRules + rule.copy(id = (shiftRules.maxOfOrNull { it.id } ?: 0) + 1); saveData(); scheduleNextAlarms(getApplication<Application>().applicationContext) }
    fun updateShiftRule(rule: ShiftRule) { shiftRules = shiftRules.map { if (it.id == rule.id) rule else it }; saveData(); scheduleNextAlarms(getApplication<Application>().applicationContext) }
    fun deleteShiftRule(rule: ShiftRule) { shiftRules = shiftRules.filter { it.id != rule.id }; saveData(); scheduleNextAlarms(getApplication<Application>().applicationContext) }
    fun nextMonth() { currentMonth = currentMonth.plusMonths(1) }
    fun previousMonth() { currentMonth = currentMonth.minusMonths(1) }
    fun resetToToday() { currentMonth = YearMonth.now() }

    fun updateTheme(theme: String) { themePref = theme; prefs.edit { putString("theme", theme) } }
    fun updateDynamicColor(use: Boolean) { useDynamicColor = use; prefs.edit { putBoolean("dynamic_color", use) } }
    fun updateHue(hue: Float) { customHue = hue; prefs.edit { putFloat("custom_hue", hue) } }
    fun updateLedMode(use: Boolean) { ledMode = use; prefs.edit { putBoolean("led_mode", use) } }
    fun updateCurrency(currency: String) { currencyPref = currency; prefs.edit { putString("currency", currency) } }
    fun updateAutoComplete(use: Boolean) { autoCompletePref = use; prefs.edit { putBoolean("auto_complete", use) }; runAutoCompleteCheck() }
    fun updateLanguage(lang: String) { languagePref = lang; prefs.edit { putString("language", lang) } }

    fun runAutoCompleteCheck() {
        if (!autoCompletePref) return
        var changed = false
        val currentLogs = loggedShifts.toMutableMap(); val today = LocalDate.now(); val now = LocalTime.now()
        for (i in 0..30) {
            val date = today.minusDays(i.toLong())
            if (!currentLogs.containsKey(date)) {
                val rule = getPlannedShiftForDate(date)
                if (rule != null && (date.isBefore(today) || (date == today && now.isAfter(rule.endTime)))) {
                    currentLogs[date] = LoggedShift(date, rule.name, rule.calculatedHours * rule.hourlyWage, rule.calculatedHours)
                    changed = true
                }
            }
        }
        if (changed) { loggedShifts = currentLogs; saveData() }
    }

    @SuppressLint("ScheduleExactAlarm")
    private fun scheduleNextAlarms(context: Context) {
        val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val today = LocalDate.now()
        for (i in 0..7) {
            val date = today.plusDays(i.toLong())
            if (!loggedShifts.containsKey(date)) {
                getPlannedShiftForDate(date)?.let { rule ->
                    val trigger = LocalDateTime.of(date, rule.endTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    if (trigger > System.currentTimeMillis()) {
                        val intent = Intent(context, ShiftNotificationReceiver::class.java).apply { putExtra("SHIFT_DATE", date.toString()); putExtra("SHIFT_NAME", rule.name); putExtra("AUTO_COMPLETE", autoCompletePref) }
                        val pendingIntent = PendingIntent.getBroadcast(context, date.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        try { alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent) } catch (_: SecurityException) { }
                    }
                }
            }
        }
    }

    fun triggerTestNotification(context: Context) {
        context.sendBroadcast(Intent(context, ShiftNotificationReceiver::class.java).apply { putExtra("SHIFT_DATE", LocalDate.now().toString()); putExtra("SHIFT_NAME", "Test Shift"); putExtra("AUTO_COMPLETE", autoCompletePref) })
        Toast.makeText(context, context.getString(R.string.toast_test_notif), Toast.LENGTH_SHORT).show()
    }

    fun exportToCsv(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { writer ->
                    writer.append("Date,Shift Name,Hours Worked,Earned\n")
                    loggedShifts.values.sortedByDescending { it.date }.forEach { writer.append("${it.date},\"${it.shiftName}\",${it.hours},${it.earned}\n") }
                }
            }
            Toast.makeText(context, context.getString(R.string.toast_export_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.toast_export_fail, e.message), Toast.LENGTH_LONG).show() }
    }

    fun createBackup(context: Context, uri: Uri) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { os -> OutputStreamWriter(os).use { writer -> writer.write(gson.toJson(mapOf("rules" to shiftRules, "logs" to loggedShifts.values.toList()))) } }
            Toast.makeText(context, context.getString(R.string.toast_backup_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, context.getString(R.string.toast_backup_fail, e.message), Toast.LENGTH_LONG).show() }
    }

    fun restoreBackup(context: Context, uri: Uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonObject = JsonParser.parseString(inputStream.bufferedReader().use { it.readText() }).asJsonObject
                shiftRules = gson.fromJson(jsonObject.get("rules").toString(), object : TypeToken<List<ShiftRule>>() {}.type) ?: emptyList()
                val loadedLogs: List<LoggedShift> = gson.fromJson(jsonObject.get("logs").toString(), object : TypeToken<List<LoggedShift>>() {}.type) ?: emptyList()
                loggedShifts = loadedLogs.associateBy { it.date }
                saveData(); scheduleNextAlarms(context)
                Toast.makeText(context, context.getString(R.string.toast_restore_success), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) { Toast.makeText(context, context.getString(R.string.toast_restore_fail), Toast.LENGTH_LONG).show() }
    }

    private fun saveData() { prefs.edit { putString("rules", gson.toJson(shiftRules)); putString("logs", gson.toJson(loggedShifts.values.toList())) } }
    private fun loadData() {
        try {
            val rJson = prefs.getString("rules", null); val lJson = prefs.getString("logs", null)
            if (rJson != null) shiftRules = gson.fromJson(rJson, object : TypeToken<List<ShiftRule>>() {}.type) ?: emptyList()
            if (lJson != null) {
                val loadedLogs: List<LoggedShift> = gson.fromJson(lJson, object : TypeToken<List<LoggedShift>>() {}.type) ?: emptyList()
                loggedShifts = loadedLogs.associateBy { it.date }
            }
        } catch (_: Exception) { prefs.edit().clear().apply(); shiftRules = emptyList(); loggedShifts = emptyMap() }
    }
}

fun blendColors(base: Color, blend: Color, ratio: Float) = Color((base.red * (1f - ratio) + blend.red * ratio).coerceIn(0f, 1f), (base.green * (1f - ratio) + blend.green * ratio).coerceIn(0f, 1f), (base.blue * (1f - ratio) + blend.blue * ratio).coerceIn(0f, 1f), 1f)

class ShiftNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dateStr = intent.getStringExtra("SHIFT_DATE") ?: return
        val shiftName = intent.getStringExtra("SHIFT_NAME") ?: "Your Shift"
        val autoComplete = intent.getBooleanExtra("AUTO_COMPLETE", false)
        val openPending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }, PendingIntent.FLAG_IMMUTABLE)
        val confirmPending = PendingIntent.getActivity(context, abs(dateStr.hashCode()), Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK; putExtra("CONFIRM_SHIFT_DATE", dateStr) }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationText = if (autoComplete) context.getString(R.string.notif_auto_complete) else context.getString(R.string.notif_manual, shiftName)
        val notificationBuilder = NotificationCompat.Builder(context, "shift_alerts").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(context.getString(R.string.notif_title)).setContentText(notificationText).setPriority(NotificationCompat.PRIORITY_MAX).setContentIntent(openPending).setAutoCancel(true)
        if (!autoComplete) notificationBuilder.addAction(android.R.drawable.ic_input_add, context.getString(R.string.notif_action_log), confirmPending)

        val manager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(abs(dateStr.hashCode()), notificationBuilder.build())
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel("shift_alerts", getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_HIGH).apply { description = getString(R.string.notif_channel_desc) })
        }

        val currentIntent = intent
        setContent {
            val viewModel: ShiftViewModel = viewModel()
            val context = LocalContext.current

            val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            LaunchedEffect(currentIntent) {
                currentIntent.getStringExtra("CONFIRM_SHIFT_DATE")?.let {
                    val date = LocalDate.parse(it)
                    if (!viewModel.loggedShifts.containsKey(date)) {
                        viewModel.toggleShiftForDate(date)
                        Toast.makeText(context, context.getString(R.string.toast_shift_logged), Toast.LENGTH_SHORT).show()
                    }
                    val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notifManager.cancel(abs(it.hashCode()))
                }
            }

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.runAutoCompleteCheck() }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val customPrimary = Color.hsv(viewModel.customHue, 0.7f, 0.9f)
            val darkTheme = when (viewModel.themePref) { "Dark" -> true; "Light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            val darkBg = if (viewModel.ledMode) Color.Black else blendColors(Color(18, 18, 18), customPrimary, 0.12f)
            val darkSurface = if (viewModel.ledMode) blendColors(Color.Black, customPrimary, 0.05f) else blendColors(Color(30, 30, 30), customPrimary, 0.18f)
            val lightBg = blendColors(Color(250, 250, 250), customPrimary, 0.05f)
            val lightSurface = blendColors(Color.White, customPrimary, 0.10f)
            val colorScheme = if (viewModel.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dynamicScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                if (darkTheme && viewModel.ledMode) dynamicScheme.copy(background = Color.Black, surface = blendColors(Color.Black, dynamicScheme.primary, 0.05f), surfaceVariant = blendColors(Color.Black, dynamicScheme.primary, 0.05f)) else dynamicScheme
            } else {
                if (darkTheme) darkColorScheme(primary = customPrimary, background = darkBg, surface = darkSurface, surfaceVariant = darkSurface) else lightColorScheme(primary = customPrimary, background = lightBg, surface = lightSurface, surfaceVariant = lightSurface)
            }
            MaterialTheme(colorScheme = colorScheme) { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainAppNavigation(viewModel) } }
        }
    }
}

@Composable
fun MainAppNavigation(viewModel: ShiftViewModel) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val view = LocalView.current
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationBarItem(icon = { Icon(Icons.Filled.DateRange, stringResource(R.string.nav_dashboard)) }, label = { Text(stringResource(R.string.nav_dashboard)) }, selected = currentRoute == "dashboard", onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); navController.navigate("dashboard") { popUpTo(0) } })
                NavigationBarItem(icon = { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.nav_log)) }, label = { Text(stringResource(R.string.nav_log)) }, selected = currentRoute == "log", onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); navController.navigate("log") { popUpTo(0) } })
                NavigationBarItem(icon = { Icon(Icons.Filled.Settings, stringResource(R.string.nav_settings)) }, label = { Text(stringResource(R.string.nav_settings)) }, selected = currentRoute == "settings", onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); navController.navigate("settings") { popUpTo(0) } })
            }
        }
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = "dashboard", modifier = Modifier.padding(innerPadding), enterTransition = { slideInVertically(initialOffsetY = { 80 }) + fadeIn() }, exitTransition = { fadeOut() }) {
            composable("dashboard") { DashboardScreen(viewModel) }
            composable("log") { LogScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: ShiftViewModel) {
    val context = LocalContext.current
    val view = LocalView.current
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) viewModel.exportToCsv(context, uri) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) viewModel.createBackup(context, uri) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.restoreBackup(context, uri) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.theme), fontWeight = FontWeight.Medium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(stringResource(R.string.theme_system) to "System", stringResource(R.string.theme_light) to "Light", stringResource(R.string.theme_dark) to "Dark").forEach { (display, value) -> FilterChip(selected = viewModel.themePref == value, onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.updateTheme(value) }, label = { Text(display) }) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column { Text(stringResource(R.string.amoled_black), fontWeight = FontWeight.Medium); Text(stringResource(R.string.amoled_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = viewModel.ledMode, onCheckedChange = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.updateLedMode(it) })
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.material_you), fontWeight = FontWeight.Medium)
                        Switch(checked = viewModel.useDynamicColor, onCheckedChange = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.updateDynamicColor(it) })
                    }
                }
                val isColorSelectionEnabled = !viewModel.useDynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.custom_accent), fontWeight = FontWeight.Medium, color = if (isColorSelectionEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Box(contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp).clip(CircleShape).background(if (isColorSelectionEnabled) Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) else Brush.horizontalGradient(listOf(Color.Gray, Color.DarkGray))))
                        Slider(value = viewModel.customHue, onValueChange = { viewModel.updateHue(it) }, valueRange = 0f..360f, enabled = isColorSelectionEnabled, colors = SliderDefaults.colors(thumbColor = if (isColorSelectionEnabled) Color.hsv(viewModel.customHue, 1f, 1f) else Color.LightGray, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent))
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.automation), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) { Text(stringResource(R.string.auto_complete), fontWeight = FontWeight.Medium); Text(stringResource(R.string.auto_complete_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(checked = viewModel.autoCompletePref, onCheckedChange = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.updateAutoComplete(it) })
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.localization), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.currency_override), fontWeight = FontWeight.Medium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("System", "€", "$", "£", "¥", "₺", "₹", "₽", "₩", "C$", "A$", "CHF").chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { cur -> FilterChip(selected = viewModel.currencyPref == cur, onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.updateCurrency(cur) }, label = { Text(cur) }) } }
                    }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.data_management), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); exportCsvLauncher.launch("CleanShift_Export_${LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_export)) }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); backupLauncher.launch("CleanShift_Backup_${LocalDate.now()}.json") }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_backup)) }
                    OutlinedButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); restoreLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.btn_restore)) }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.dev_debug), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.triggerTestNotification(context) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.btn_trigger_test), color = MaterialTheme.colorScheme.onError) }
            }
        }
    }
}

@Composable
fun DashboardScreen(viewModel: ShiftViewModel) {
    var showDialog by remember { mutableStateOf(false) }
    var ruleToEdit by remember { mutableStateOf<ShiftRule?>(null) }
    var eggClicks by remember { mutableIntStateOf(0) }
    var showDog by remember { mutableStateOf(false) }
    val view = LocalView.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.height(180.dp)) { StatsCardSide(viewModel, onEggClick = { eggClicks++; if (eggClicks >= 15) { showDog = true; eggClicks = 0 } }) }
                Box(modifier = Modifier.weight(1f)) { ShiftConfigSide(viewModel, onAddClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); ruleToEdit = null; showDialog = true }, onEditClick = { r -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); ruleToEdit = r; showDialog = true }) }
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Column { CalendarSection(viewModel); Spacer(Modifier.height(40.dp)) }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { StatsCardSide(viewModel, onEggClick = { eggClicks++; if (eggClicks >= 15) { showDog = true; eggClicks = 0 } }) }
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) { ShiftConfigSide(viewModel, onAddClick = { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); ruleToEdit = null; showDialog = true }, onEditClick = { r -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); ruleToEdit = r; showDialog = true }) }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CalendarSection(viewModel)
            }
        }
    }

    if (showDog) DogEasterEgg(onDismiss = { showDog = false })
    if (showDialog) ShiftRuleDialog(initialRule = ruleToEdit, viewModelCustomHue = viewModel.customHue, onDismiss = { showDialog = false }, onSave = { r -> if (ruleToEdit == null) viewModel.addShiftRule(r) else viewModel.updateShiftRule(r); showDialog = false }, onDelete = { r -> viewModel.deleteShiftRule(r); showDialog = false })
}

@Composable
fun StatsCardSide(viewModel: ShiftViewModel, onEggClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEggClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(12.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("${viewModel.currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${viewModel.currentMonth.year}".uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(viewModel.formatMoney(viewModel.monthlyEarned), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${String.format(Locale.getDefault(), "%.1f", viewModel.monthlyHours)} hrs  •  ${viewModel.monthlyShiftsWorked} shifts", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f))
            }
            Column {
                Text(stringResource(R.string.upcoming_this_month), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.planned_shifts, viewModel.plannedCount) + " (${String.format(Locale.getDefault(), "%.1f", viewModel.plannedHours)}h)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = viewModel.formatMoney(viewModel.plannedEarnings),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ShiftConfigSide(viewModel: ShiftViewModel, onAddClick: () -> Unit, onEditClick: (ShiftRule) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.shifts_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); IconButton(onClick = onAddClick, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) }
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(count = viewModel.shiftRules.size) { index ->
                val r = viewModel.shiftRules[index]
                val h = r.colorHue ?: viewModel.customHue
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.clickable { onEditClick(r) }) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.width(4.dp).height(30.dp).background(Color.hsv(h, 0.7f, 0.9f)))
                        Text(r.name, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun DogEasterEgg(onDismiss: () -> Unit) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.85f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))

    Dialog(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }, contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(id = R.drawable.poncuk_egg),
                contentDescription = "Dog",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(280.dp)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .clip(RoundedCornerShape(28.dp))
                    .border(4.dp, Color.White, RoundedCornerShape(28.dp))
                    .clickable(interactionSource = interactionSource, indication = null) {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
            )
        }
    }
}

@Composable
fun CalendarSection(viewModel: ShiftViewModel) {
    val start = viewModel.currentMonth.atDay(1).dayOfWeek.value - 1
    val days = viewModel.currentMonth.lengthOfMonth()
    val today = LocalDate.now(); val view = LocalView.current
    var off by remember { mutableFloatStateOf(0f) }
    Column(modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { if (off > 150) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); viewModel.previousMonth() } else if (off < -150) { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); viewModel.nextMonth() }; off = 0f }, onHorizontalDrag = { change, d -> change.consume(); off += d }) }) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); viewModel.previousMonth() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null) }
            Text("${viewModel.currentMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${viewModel.currentMonth.year}", fontWeight = FontWeight.Bold, modifier = Modifier.clickable { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.resetToToday() })
            IconButton(onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); viewModel.nextMonth() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }
        }
        LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.fillMaxWidth(), userScrollEnabled = false) {
            items(start) { Spacer(Modifier.fillMaxSize()) }
            items(days) { i ->
                val d = viewModel.currentMonth.atDay(i + 1); val p = viewModel.getPlannedShiftForDate(d); val l = viewModel.loggedShifts.containsKey(d)
                CalendarDayCell(d, p, viewModel.customHue, l, d == today) { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); viewModel.toggleShiftForDate(d) }
            }
            items(42 - (start + days)) { Spacer(Modifier.fillMaxSize()) }
        }
    }
}

@Composable
fun CalendarDayCell(d: LocalDate, p: ShiftRule?, h: Float, l: Boolean, isT: Boolean, onClick: () -> Unit) {
    val dot = if (p != null) Color.hsv(p.colorHue ?: h, 0.7f, 0.9f) else MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.aspectRatio(1f).padding(2.dp).clip(CircleShape).clickable(enabled = p != null || l) { onClick() }.then(if (isT) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(d.dayOfMonth.toString(), fontSize = 12.sp, color = if (p != null || l) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(0.4f))
        if (p != null || l) Box(Modifier.size(4.dp).clip(CircleShape).background(if (l) dot else dot.copy(0.3f)))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(viewModel: ShiftViewModel) {
    val logs = viewModel.loggedShifts.values.groupBy { YearMonth.from(it.date) }.toSortedMap(compareByDescending { it })
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.shift_history), fontWeight = FontWeight.Bold) }) }) { p ->
        if (logs.isEmpty()) Box(Modifier.fillMaxSize().padding(p), Alignment.Center) { Text(stringResource(R.string.no_shifts_logged)) }
        else LazyColumn(Modifier.fillMaxSize().padding(p).padding(horizontal = 16.dp)) {
            logs.forEach { (m, s) ->
                item { Column { Text("${m.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${m.year}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text(stringResource(R.string.total_earned, viewModel.formatMoney(s.sumOf { it.earned })), style = MaterialTheme.typography.bodyMedium); HorizontalDivider(Modifier.padding(vertical = 8.dp)) } }

                val sortedShifts = s.sortedByDescending { it.date }
                items(count = sortedShifts.size) { index ->
                    val shift = sortedShifts[index]
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(shift.shiftName, fontWeight = FontWeight.Bold); Text(shift.date.toString(), fontSize = 12.sp) }; Column(horizontalAlignment = Alignment.End) { Text(viewModel.formatMoney(shift.earned), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary); Text("${String.format(Locale.getDefault(), "%.1f", shift.hours)}h", fontSize = 12.sp) } } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShiftRuleDialog(initialRule: ShiftRule?, viewModelCustomHue: Float, onDismiss: () -> Unit, onSave: (ShiftRule) -> Unit, onDelete: (ShiftRule) -> Unit) {
    val ctx = LocalContext.current; var name by remember { mutableStateOf(initialRule?.name ?: "") }; var startD by remember { mutableStateOf(initialRule?.startDate ?: LocalDate.now()) }
    var startT by remember { mutableStateOf(initialRule?.startTime ?: LocalTime.of(9, 0)) }; var endT by remember { mutableStateOf(initialRule?.endTime ?: LocalTime.of(17, 0)) }
    var rep by remember { mutableStateOf(initialRule?.repeatType ?: RepeatType.WEEKLY) }; var wage by remember { mutableStateOf(initialRule?.hourlyWage?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "15.00") }
    var hue by remember { mutableFloatStateOf(initialRule?.colorHue ?: viewModelCustomHue) }; var exp by remember { mutableStateOf(false) }
    val hrs = remember(startT, endT) { var m = Duration.between(startT, endT).toMinutes(); if (m < 0) m += 1440; m / 60.0 }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (initialRule == null) stringResource(R.string.add_shift) else stringResource(R.string.edit_shift)) }, confirmButton = { TextButton(onClick = { onSave(ShiftRule(initialRule?.id ?: 0, name, startD, startT, endT, rep, 1, wage.replace(",", ".").toDoubleOrNull() ?: 0.0, hue)) }) { Text(stringResource(R.string.btn_save)) } }, dismissButton = { Row { if (initialRule != null) TextButton(onClick = { onDelete(initialRule) }) { Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.shift_name)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.shift_color_marker), fontSize = 12.sp, fontWeight = FontWeight.Bold); Box(contentAlignment = Alignment.Center) { Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))); Slider(hue, { hue = it }, valueRange = 0f..360f, colors = SliderDefaults.colors(thumbColor = Color.hsv(hue, 1f, 1f), activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent)) }
            OutlinedTextField(startD.toString(), {}, readOnly = true, label = { Text(stringResource(R.string.start_date)) }, modifier = Modifier.fillMaxWidth().clickable { DatePickerDialog(ctx, { _, y, m, d -> startD = LocalDate.of(y, m + 1, d) }, startD.year, startD.monthValue - 1, startD.dayOfMonth).show() }, enabled = false, colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(startT.toString(), {}, readOnly = true, modifier = Modifier.weight(1f).clickable { TimePickerDialog(ctx, { _, h, m -> startT = LocalTime.of(h, m) }, startT.hour, startT.minute, true).show() }, enabled = false, colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline))
                OutlinedTextField(endT.toString(), {}, readOnly = true, modifier = Modifier.weight(1f).clickable { TimePickerDialog(ctx, { _, h, m -> endT = LocalTime.of(h, m) }, endT.hour, endT.minute, true).show() }, enabled = false, colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MaterialTheme.colorScheme.onSurface, disabledBorderColor = MaterialTheme.colorScheme.outline))
            }
            Text(stringResource(R.string.duration_format, String.format(Locale.getDefault(), "%.1f", hrs)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            ExposedDropdownMenuBox(exp, { exp = !exp }) { OutlinedTextField(stringResource(rep.labelRes), {}, readOnly = true, label = { Text(stringResource(R.string.repetition)) }, modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, true)); ExposedDropdownMenu(exp, { exp = false }) { RepeatType.entries.forEach { t -> DropdownMenuItem({ Text(stringResource(t.labelRes)) }, { rep = t; exp = false }) } } }
            OutlinedTextField(wage, { wage = it }, label = { Text(stringResource(R.string.hourly_wage)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        } }
    )
}