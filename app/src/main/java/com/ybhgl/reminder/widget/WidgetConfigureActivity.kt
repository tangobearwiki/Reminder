package com.ybhgl.reminder.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ybhgl.reminder.R
import com.ybhgl.reminder.ReminderApplication
import com.ybhgl.reminder.data.ReminderItem
import com.ybhgl.reminder.data.ReminderType
import com.ybhgl.reminder.ui.theme.ReminderTheme
import kotlinx.coroutines.flow.first

class WidgetConfigureActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        
        setResult(RESULT_CANCELED)

        val extras = intent.extras
        if (extras != null) {
            appWidgetId = extras.getInt(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val providerClassName = info?.provider?.className ?: ""

        val isSingleSelection = providerClassName.contains("ReminderWidget1x2") || providerClassName.contains("ReminderWidget2x2")

        val repository = (applicationContext as ReminderApplication).container.reminderRepository

        val initialOpacity = WidgetConfigStore.getWidgetOpacity(this, appWidgetId)
        val initialSelectedId = WidgetConfigStore.get1x2Or2x2Config(this, appWidgetId)
        val initialFilterType = if (!isSingleSelection) WidgetConfigStore.get4x2FilterType(this, appWidgetId) else "all"
        val initialCustomIds = if (!isSingleSelection) WidgetConfigStore.get4x2CustomIds(this, appWidgetId) else emptySet()

        setContent {
            ReminderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WidgetConfigureScreen(
                        appWidgetId = appWidgetId,
                        isSingleSelection = isSingleSelection,
                        initialOpacity = initialOpacity,
                        initialSelectedId = initialSelectedId,
                        initialFilterType = initialFilterType,
                        initialCustomIds = initialCustomIds,
                        onCancel = { finish() },
                        onSave = { selectedId, filterType, customIds, opacity, items ->
                            // Save opacity first for any widget
                            WidgetConfigStore.saveWidgetOpacity(this@WidgetConfigureActivity, appWidgetId, opacity)

                            if (isSingleSelection) {
                                WidgetConfigStore.save1x2Or2x2Config(this@WidgetConfigureActivity, appWidgetId, selectedId)
                                
                                val is1x2 = providerClassName.contains("ReminderWidget1x2")
                                if (is1x2) {
                                    WidgetUpdateHelper.update1x2WidgetWithData(this@WidgetConfigureActivity, appWidgetManager, appWidgetId, opacity, selectedId, items)
                                } else {
                                    WidgetUpdateHelper.update2x2WidgetWithData(this@WidgetConfigureActivity, appWidgetManager, appWidgetId, opacity, selectedId, items)
                                }
                                val updateIntent = Intent(this@WidgetConfigureActivity, if (is1x2) ReminderWidget1x2::class.java else ReminderWidget2x2::class.java).apply {
                                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                                }
                                sendBroadcast(updateIntent)
                            } else {
                                WidgetConfigStore.save4x2Config(this@WidgetConfigureActivity, appWidgetId, filterType, customIds)
                                
                                val updateIntent = Intent(this@WidgetConfigureActivity, ReminderWidget4x2::class.java).apply {
                                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                                }
                                sendBroadcast(updateIntent)
                                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
                            }

                            val resultValue = Intent().apply {
                                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                            setResult(RESULT_OK, resultValue)
                            finish()
                        },
                        loadReminders = { repository.getAllRemindersStream().first() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetConfigureScreen(
    appWidgetId: Int,
    isSingleSelection: Boolean,
    initialOpacity: Int = 100,
    initialSelectedId: Int = -1,
    initialFilterType: String = "all",
    initialCustomIds: Set<Int> = emptySet(),
    onCancel: () -> Unit,
    onSave: (selectedId: Int, filterType: String, customIds: Set<Int>, opacity: Int, reminders: List<ReminderItem>) -> Unit,
    loadReminders: suspend () -> List<ReminderItem>
) {
    var reminders by remember { mutableStateOf<List<ReminderItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val widgetCardShape = RoundedCornerShape(16.dp)

    // Transparency State (0-100)
    var opacity by remember { mutableStateOf(initialOpacity.toFloat()) }

    // Selection States for 1x2 / 2x2
    var selectedReminderId by remember { mutableIntStateOf(initialSelectedId) }

    // Selection States for 4x2
    var filterType by remember { mutableStateOf(initialFilterType) }
    var customSelectedIds by remember { mutableStateOf(initialCustomIds) }

    // ===== 自定义主题状态 =====
    var showCustomization by remember { mutableStateOf(false) }
    var bgType by remember { mutableStateOf(WidgetConfigStore.BG_TYPE_DEFAULT) }
    var bgColorHex by remember { mutableStateOf("") }
    var accentColorHex by remember { mutableStateOf("") }
    var customTitle by remember { mutableStateOf("") }
    var showDate by remember { mutableStateOf(true) }
    var showLabel by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        reminders = loadReminders()
        if (reminders.isNotEmpty() && selectedReminderId == -1) {
            selectedReminderId = reminders.first().id
        }
        isLoading = false
    }

    // 颜色选择器弹窗状态
    var showBgColorPicker by remember { mutableStateOf(false) }
    var showAccentColorPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isSingleSelection) "配置桌面小部件" else "配置列表展示") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (reminders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无任何提醒，请先在应用内添加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCancel) {
                        Text("返回")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                // 滚动内容
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ===== 背景不透明度 =====
                    item {
                        Card(
                            shape = widgetCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("背景不透明度", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Text("${opacity.toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(8.dp))
                                Slider(value = opacity, onValueChange = { opacity = it }, valueRange = 0f..100f, steps = 19)
                                Text("调整背景的不透明度，数值越低越透明", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // ===== 自定义主题折叠面板 =====
                    item {
                        Card(
                            shape = widgetCardShape,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showCustomization = !showCustomization },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("主题自定义", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                        Text(if (showCustomization) "点击收起" else "自定义背景、颜色、标题等", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(
                                        imageVector = if (showCustomization) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (showCustomization) {
                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))

                                    // 背景类型选择
                                    Text("背景样式", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val bgOptions = listOf(
                                            WidgetConfigStore.BG_TYPE_DEFAULT to "默认",
                                            WidgetConfigStore.BG_TYPE_COLOR to "纯色",
                                            WidgetConfigStore.BG_TYPE_IMAGE to "图片"
                                        )
                                        bgOptions.forEach { (type, label) ->
                                            FilterChip(
                                                selected = bgType == type,
                                                onClick = { bgType = type },
                                                label = { Text(label, style = MaterialTheme.typography.bodySmall) }
                                            )
                                        }
                                    }

                                    // 纯色背景颜色选择
                                    if (bgType == WidgetConfigStore.BG_TYPE_COLOR) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clickable { showBgColorPicker = true },
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("背景颜色", style = MaterialTheme.typography.bodyMedium)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val previewColor = try {
                                                    android.graphics.Color.parseColor(if (bgColorHex.isEmpty()) "#1E88E5" else bgColorHex)
                                                } catch (e: Exception) { android.graphics.Color.parseColor("#1E88E5") }
                                                Box(
                                                    Modifier
                                                        .size(20.dp)
                                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                                        .background(Color(previewColor))
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(if (bgColorHex.isEmpty()) "选择颜色" else bgColorHex.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    // 图片背景提示
                                    if (bgType == WidgetConfigStore.BG_TYPE_IMAGE) {
                                        Spacer(Modifier.height(8.dp))
                                        Text("提示：图片背景需在应用内设置更多选项，此处仅支持选择背景类型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))

                                    // 自定义标题
                                    Text("自定义标题（可选）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    OutlinedTextField(
                                        value = customTitle,
                                        onValueChange = { customTitle = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("留空则使用提醒标题") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp)
                                    )

                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))

                                    // 强调色
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { showAccentColorPicker = true },
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("数字强调色", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                            Text("为空则跟随提醒类型默认色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val previewColor = try {
                                                android.graphics.Color.parseColor(if (accentColorHex.isEmpty()) "#1E88E5" else accentColorHex)
                                            } catch (e: Exception) { android.graphics.Color.parseColor("#1E88E5") }
                                            Box(
                                                Modifier.size(20.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(previewColor))
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (accentColorHex.isEmpty()) "默认" else accentColorHex.uppercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(12.dp))

                                    // 显示选项
                                    Text("显示选项", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("显示日期", style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showDate, onCheckedChange = { showDate = it })
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("显示标签（还有/第/生日）", style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = showLabel, onCheckedChange = { showLabel = it })
                                    }
                                }
                            }
                        }
                    }

                    // ===== 提醒选择 =====
                    if (isSingleSelection) {
                        item {
                            Text("选择要显示的提醒：", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        }
                        items(reminders) { reminder ->
                            val isSelected = reminder.id == selectedReminderId
                            Card(
                                shape = widgetCardShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                modifier = Modifier.fillMaxWidth().clip(widgetCardShape).selectable(
                                    selected = isSelected,
                                    onClick = { selectedReminderId = reminder.id }
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(reminder.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(
                                            when (reminder.type) {
                                                ReminderType.ANNUAL -> "倒数日 · ${reminder.date}"
                                                ReminderType.COUNT_UP -> "正数日 · ${reminder.date}"
                                                ReminderType.BIRTHDAY -> "生日 · ${reminder.date}"
                                            },
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    RadioButton(selected = isSelected, onClick = { selectedReminderId = reminder.id })
                                }
                            }
                        }
                    } else {
                        item {
                            Text("选择展示哪些提醒：", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        }
                        item {
                            Card(
                                shape = widgetCardShape,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(8.dp)) {
                                    val filterOptions = listOf(
                                        "all" to "展示所有提醒",
                                        "countdown" to "仅展示倒数日",
                                        "countup" to "仅展示正数日",
                                        "birthday" to "仅展示生日",
                                        "custom" to "自由选择"
                                    )
                                    filterOptions.forEach { (optionType, label) ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { filterType = optionType }.padding(vertical = 10.dp, horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = filterType == optionType, onClick = { filterType = optionType })
                                            Spacer(Modifier.width(8.dp))
                                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = if (filterType == optionType) FontWeight.Bold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                        }
                        if (filterType == "custom") {
                            item {
                                Text("请勾选要显示的提醒（可多选）：", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
                            }
                            items(reminders) { reminder ->
                                val isChecked = customSelectedIds.contains(reminder.id)
                                Card(
                                    shape = widgetCardShape,
                                    colors = CardDefaults.cardColors(containerColor = if (isChecked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth().clip(widgetCardShape).clickable {
                                        customSelectedIds = if (isChecked) customSelectedIds - reminder.id else customSelectedIds + reminder.id
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(reminder.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                                            Text(
                                                when (reminder.type) { ReminderType.ANNUAL -> "倒数日"; ReminderType.COUNT_UP -> "正数日"; ReminderType.BIRTHDAY -> "生日" },
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Checkbox(checked = isChecked, onCheckedChange = { checked ->
                                            customSelectedIds = if (checked == true) customSelectedIds + reminder.id else customSelectedIds - reminder.id
                                        })
                                    }
                                }
                            }
                        }
                    }

                    // 底部留白
                    item { Spacer(Modifier.height(80.dp)) }
                }

                // 底部按钮
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
                        Button(
                            onClick = {
                                // 保存自定义主题配置到 SharedPreferences
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                WidgetConfigStore.saveBackgroundType(ctx, appWidgetId, bgType)
                                WidgetConfigStore.saveBackgroundColor(ctx, appWidgetId, bgColorHex)
                                WidgetConfigStore.saveAccentColor(ctx, appWidgetId, accentColorHex)
                                WidgetConfigStore.saveCustomTitle(ctx, appWidgetId, customTitle)
                                WidgetConfigStore.saveShowDate(ctx, appWidgetId, showDate)
                                WidgetConfigStore.saveShowLabel(ctx, appWidgetId, showLabel)

                                if (isSingleSelection) {
                                    onSave(selectedReminderId, "", emptySet(), opacity.toInt(), reminders)
                                } else {
                                    onSave(-1, filterType, customSelectedIds, opacity.toInt(), reminders)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = if (isSingleSelection) selectedReminderId != -1 else (filterType != "custom" || customSelectedIds.isNotEmpty())
                        ) { Text("保存") }
                    }
                }
            }
        }
    }

    // 背景颜色选择器
    if (showBgColorPicker) {
        SimpleColorPickerDialog(
            title = "选择背景颜色",
            initialHex = bgColorHex,
            onDismiss = { showBgColorPicker = false },
            onConfirm = { hex ->
                bgColorHex = hex
                showBgColorPicker = false
            }
        )
    }

    // 强调色选择器
    if (showAccentColorPicker) {
        SimpleColorPickerDialog(
            title = "选择数字强调色",
            initialHex = accentColorHex,
            onDismiss = { showAccentColorPicker = false },
            onConfirm = { hex ->
                accentColorHex = hex
                showAccentColorPicker = false
            }
        )
    }
}

@Composable
private fun SimpleColorPickerDialog(
    title: String,
    initialHex: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var hexInput by remember { mutableStateOf(initialHex.ifEmpty { "#1E88E5" }) }
    var selectedPreset by remember { mutableStateOf(initialHex) }
    val presets = listOf(
        "#1E88E5", "#F28C20", "#E53935", "#4CAF50",
        "#9C27B0", "#00BCD4", "#FF5722", "#607D8B",
        "#FFFFFF", "#000000", "#2C2C2C", "#ECEFF1"
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                // 预设颜色
                Text("预设颜色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { hex ->
                                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (e: Exception) { Color.Gray }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(color)
                                        .border(if (selectedPreset.equals(hex, ignoreCase = true)) 3.dp else 1.dp,
                                            if (selectedPreset.equals(hex, ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                            androidx.compose.foundation.shape.CircleShape)
                                        .clickable {
                                            selectedPreset = hex
                                            hexInput = hex
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selectedPreset.equals(hex, ignoreCase = true)) {
                                        Icon(Icons.Default.Check, contentDescription = null,
                                            tint = if (hex == "#FFFFFF" || hex == "#ECEFF1") Color.Black else Color.White,
                                            modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it.take(7) },
                    label = { Text("HEX 颜色代码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(hexInput) }) { Text("确定") }
                }
            }
        }
    }
}
