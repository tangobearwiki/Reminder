package com.ybhgl.reminder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.ybhgl.reminder.MainActivity
import com.ybhgl.reminder.R
import com.ybhgl.reminder.ReminderApplication
import com.ybhgl.reminder.data.ReminderItem
import com.ybhgl.reminder.data.ReminderType
import com.ybhgl.reminder.util.CalendarUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.min

data class WidgetDisplayInfo(
    val title: String,
    val label: String,
    val days: String,
    val unit: String,
    val dateString: String,
    val accentColorResId: Int
)

object WidgetUpdateHelper {

    fun getFeaturedReminder(items: List<ReminderItem>): ReminderItem? {
        if (items.isEmpty()) return null
        val pinned = items.filter { it.isPinned }
        if (pinned.isNotEmpty()) {
            return pinned.first()
        }
        val today = LocalDate.now()
        val upcoming = items.filter { it.type != ReminderType.COUNT_UP }
            .mapNotNull { item ->
                val nextDate = CalendarUtil.calculateNextTargetDate(item)
                if (nextDate != null) {
                    item to ChronoUnit.DAYS.between(today, nextDate)
                } else {
                    null
                }
            }
            .sortedBy { it.second }

        if (upcoming.isNotEmpty()) {
            return upcoming.first().first
        }
        return items.firstOrNull()
    }

    fun getDisplayInfo(context: Context, reminder: ReminderItem): WidgetDisplayInfo {
        val today = LocalDate.now()
        val title = reminder.title
        var label = "还有"
        var days = "0"
        var unit = "天"
        var dateString = ""

        val accentColorResId = when (reminder.type) {
            ReminderType.ANNUAL -> R.color.widget_accent_annual
            ReminderType.COUNT_UP -> R.color.widget_accent_count_up
            ReminderType.BIRTHDAY -> R.color.widget_accent_birthday
        }

        when (reminder.type) {
            ReminderType.ANNUAL -> {
                val nextDate = CalendarUtil.calculateNextTargetDate(reminder)
                if (nextDate == null) {
                    val daysPassed = ChronoUnit.DAYS.between(reminder.date, today).toInt().coerceAtLeast(0)
                    label = "已过"
                    days = daysPassed.toString()
                    dateString = if (reminder.isLunar) CalendarUtil.formatLunarDateShort(reminder.date) else reminder.date.toString()
                } else {
                    val daysRemaining = ChronoUnit.DAYS.between(today, nextDate).toInt()
                    if (daysRemaining == 0) {
                        label = "就是"
                        days = "今"
                        unit = ""
                    } else {
                        label = "还有"
                        days = daysRemaining.toString()
                    }
                    dateString = if (reminder.isLunar) CalendarUtil.formatLunarDateShort(nextDate) else nextDate.toString()
                }
            }

            ReminderType.COUNT_UP -> {
                val isIncludeStartDay = reminder.notificationConfig.includeStartDay
                val daysElapsed = if (isIncludeStartDay) {
                    ChronoUnit.DAYS.between(reminder.date, today).toInt().coerceAtLeast(0) + 1
                } else {
                    ChronoUnit.DAYS.between(reminder.date, today).toInt().coerceAtLeast(0)
                }
                label = "第"
                days = daysElapsed.toString()
                dateString = if (reminder.isLunar) CalendarUtil.formatLunarDateShort(reminder.date) else reminder.date.toString()
            }

            ReminderType.BIRTHDAY -> {
                val nextDate = CalendarUtil.calculateNextTargetDate(reminder)
                if (nextDate == null) {
                    val daysPassed = ChronoUnit.DAYS.between(reminder.date, today).toInt().coerceAtLeast(0)
                    label = "生日已过"
                    days = daysPassed.toString()
                    dateString = if (reminder.isLunar) CalendarUtil.formatLunarDateShort(reminder.date) else reminder.date.toString()
                } else {
                    val daysRemaining = ChronoUnit.DAYS.between(today, nextDate).toInt()
                    if (daysRemaining == 0) {
                        label = "生日就是"
                        days = "今"
                        unit = ""
                    } else {
                        label = "生日还有"
                        days = daysRemaining.toString()
                    }
                    dateString = if (reminder.isLunar) CalendarUtil.formatLunarDateShort(nextDate) else nextDate.toString()
                }
            }
        }

        return WidgetDisplayInfo(
            title = title,
            label = label,
            days = days,
            unit = unit,
            dateString = dateString,
            accentColorResId = accentColorResId
        )
    }

    fun applyWidgetOpacity(context: Context, views: RemoteViews, bgViewId: Int, appWidgetId: Int) {
        val opacity = WidgetConfigStore.getWidgetOpacity(context, appWidgetId)
        val alpha = (opacity * 255) / 100
        views.setInt(bgViewId, "setImageAlpha", alpha)
    }

    /**
     * 应用自定义主题配置到 RemoteViews（背景类型、背景色/图片、强调色、标题、显示选项）
     */
    fun applyCustomTheme(
        context: Context,
        views: RemoteViews,
        appWidgetId: Int,
        imageBgViewId: Int,
        overlayViewId: Int,
        bgShapeViewId: Int,
        contentViewId: Int,
        accentColorDefaultResId: Int
    ) {
        val bgType = WidgetConfigStore.getBackgroundType(context, appWidgetId)

        when (bgType) {
            WidgetConfigStore.BG_TYPE_DEFAULT -> {
                // 默认卡片背景：显示原始 shape，隐藏图片和覆盖层
                views.setViewVisibility(imageBgViewId, View.GONE)
                views.setViewVisibility(overlayViewId, View.GONE)
                views.setViewVisibility(bgShapeViewId, View.VISIBLE)
                applyWidgetOpacity(context, views, bgShapeViewId, appWidgetId)
            }
            WidgetConfigStore.BG_TYPE_COLOR -> {
                // 纯色背景：用纯色覆盖层替代 shape
                val colorHex = WidgetConfigStore.getBackgroundColor(context, appWidgetId)
                if (colorHex.isNotEmpty()) {
                    try {
                        val colorInt = android.graphics.Color.parseColor(colorHex)
                        views.setViewVisibility(overlayViewId, View.VISIBLE)
                        views.setInt(overlayViewId, "setBackgroundColor", colorInt)
                        // 隐藏 shape 和图片
                        views.setViewVisibility(bgShapeViewId, View.GONE)
                        views.setViewVisibility(imageBgViewId, View.GONE)
                    } catch (e: Exception) {
                        views.setViewVisibility(bgShapeViewId, View.VISIBLE)
                        views.setViewVisibility(overlayViewId, View.GONE)
                        views.setViewVisibility(imageBgViewId, View.GONE)
                        applyWidgetOpacity(context, views, bgShapeViewId, appWidgetId)
                    }
                } else {
                    views.setViewVisibility(bgShapeViewId, View.VISIBLE)
                    views.setViewVisibility(overlayViewId, View.GONE)
                    views.setViewVisibility(imageBgViewId, View.GONE)
                    applyWidgetOpacity(context, views, bgShapeViewId, appWidgetId)
                }
            }
            WidgetConfigStore.BG_TYPE_IMAGE -> {
                // 图片背景：加载缓存图片并设置暗色覆盖层
                val paths = WidgetConfigStore.getBackgroundImagePaths(context, appWidgetId)
                if (paths.isNotEmpty()) {
                    val photoStorage = WidgetPhotoStorage(context.applicationContext as? Context ?: context)
                    // 取第一张（或根据时间轮播）
                    val activePath = pickActivePhotoPath(paths, 24)
                    val bitmap = activePath?.let { photoStorage.loadBitmap(it) }
                    if (bitmap != null) {
                        views.setViewVisibility(imageBgViewId, View.VISIBLE)
                        views.setImageViewBitmap(imageBgViewId, bitmap)
                        // 暗色覆盖层
                        views.setViewVisibility(overlayViewId, View.VISIBLE)
                        views.setInt(overlayViewId, "setBackgroundColor", 0x66000000)
                        // 隐藏 shape
                        views.setViewVisibility(bgShapeViewId, View.GONE)
                    } else {
                        // 图片加载失败，回退到默认
                        views.setViewVisibility(bgShapeViewId, View.VISIBLE)
                        views.setViewVisibility(overlayViewId, View.GONE)
                        views.setViewVisibility(imageBgViewId, View.GONE)
                        applyWidgetOpacity(context, views, bgShapeViewId, appWidgetId)
                    }
                } else {
                    views.setViewVisibility(bgShapeViewId, View.VISIBLE)
                    views.setViewVisibility(overlayViewId, View.GONE)
                    views.setViewVisibility(imageBgViewId, View.GONE)
                    applyWidgetOpacity(context, views, bgShapeViewId, appWidgetId)
                }
            }
        }
    }

    /**
     * 根据时间段从多张图片中轮播选取一张
     */
    private fun pickActivePhotoPath(paths: List<String>, rotationHours: Int): String? {
        if (paths.isEmpty()) return null
        if (paths.size == 1) return paths.first()
        val epochHours = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).toEpochSecond() / 3600
        val index = ((epochHours / rotationHours.coerceIn(1, 168)) % paths.size).toInt()
        return paths.getOrNull(index)
    }

    /**
     * 解析自定义强调色，若为空则返回默认颜色资源 ID 对应的颜色值
     */
    private fun resolveAccentColorInt(context: Context, appWidgetId: Int, defaultResId: Int): Int {
        val hex = WidgetConfigStore.getAccentColor(context, appWidgetId)
        if (hex.isNotEmpty()) {
            try {
                return android.graphics.Color.parseColor(hex)
            } catch (e: Exception) { /* fall through */ }
        }
        return context.getColor(defaultResId)
    }

    /**
     * 根据小组件可用空间和内容长度计算响应式字号（2x2 专用）
     */
    fun getResponsiveDaysTextSize(
        context: Context,
        days: String,
        unit: String,
        widgetWidthDp: Float,
        widgetHeightDp: Float
    ): Float {
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        
        val widgetWidthPx = widgetWidthDp * density
        val widgetHeightPx = widgetHeightDp * density
        
        val headerHeightPx = 15f * scaledDensity + 8f * density * 2
        val footerHeightPx = 13f * scaledDensity + 8f * density * 2
        val dividerHeightPx = 0.6f * density
        val bodyPaddingVerticalPx = 4f * density * 2
        val bodyPaddingHorizontalPx = 8f * density * 2
        
        val availableHeightPx = widgetHeightPx - headerHeightPx - footerHeightPx - dividerHeightPx - bodyPaddingVerticalPx
        val availableWidthPx = widgetWidthPx - bodyPaddingHorizontalPx
        
        if (availableHeightPx <= 0 || availableWidthPx <= 0) {
            return 24f
        }
        
        val daysLen = days.length.coerceAtLeast(1)
        val unitLen = unit.length.coerceAtLeast(0)
        val charWidthFactor = daysLen * 0.6f + unitLen * 1.0f
        val spacingPx = 4f * density
        
        val maxByHeightPx = availableHeightPx / 1.2f
        val maxByHeightSp = maxByHeightPx / scaledDensity
        
        val maxByWidthPx = (availableWidthPx - spacingPx) / charWidthFactor
        val maxByWidthSp = maxByWidthPx / scaledDensity
        
        return (min(maxByHeightSp, maxByWidthSp) * 0.9f).coerceAtMost(80f)
    }

    /**
     * 根据小组件可用空间和内容长度计算响应式字号（1x2 专用）
     */
    fun getResponsiveDaysTextSize1x2(
        context: Context,
        days: String,
        unit: String,
        widgetWidthDp: Float,
        widgetHeightDp: Float
    ): Float {
        val density = context.resources.displayMetrics.density
        val scaledDensity = context.resources.displayMetrics.scaledDensity
        
        val widgetWidthPx = widgetWidthDp * density
        val widgetHeightPx = widgetHeightDp * density
        
        // 1x2 padding: 6dp*2 垂直, 12dp*2 水平, 右侧 margin 8dp
        val paddingVerticalPx = 6f * density * 2
        val paddingHorizontalPx = 12f * density * 2
        val rightMarginPx = 8f * density
        
        val availableHeightPx = widgetHeightPx - paddingVerticalPx
        val availableWidthPx = (widgetWidthPx - paddingHorizontalPx - rightMarginPx) * 0.5f
        
        if (availableHeightPx <= 0 || availableWidthPx <= 0) {
            return 24f
        }
        
        val daysLen = days.length.coerceAtLeast(1)
        val unitLen = unit.length.coerceAtLeast(0)
        val charWidthFactor = daysLen * 0.6f + unitLen * 1.0f
        val spacingPx = 2f * density
        
        val maxByHeightSp = availableHeightPx / (1.2f * scaledDensity)
        val maxByWidthSp = (availableWidthPx - spacingPx) / (charWidthFactor * scaledDensity)
        
        return (min(maxByHeightSp, maxByWidthSp) * 0.85f).coerceAtMost(48f)
    }

    /**
     * 获取小组件的尺寸（dp）
     */
    fun getWidgetCellSize(appWidgetManager: AppWidgetManager, appWidgetId: Int): Pair<Float, Float> {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 220).toFloat()
        val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 220).toFloat()
        return Pair(widthDp, heightDp)
    }

    suspend fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val repository = (context.applicationContext as ReminderApplication).container.reminderRepository

        withContext(Dispatchers.IO) {
            try {
                val reminders = repository.getAllRemindersList()

                val ids1x2 = appWidgetManager.getAppWidgetIds(ComponentName(context, ReminderWidget1x2::class.java))
                for (appWidgetId in ids1x2) {
                    val opacity = WidgetConfigStore.getWidgetOpacity(context, appWidgetId)
                    val configuredId = WidgetConfigStore.get1x2Or2x2Config(context, appWidgetId)
                    update1x2WidgetWithData(context, appWidgetManager, appWidgetId, opacity, configuredId, reminders)
                }

                val ids2x2 = appWidgetManager.getAppWidgetIds(ComponentName(context, ReminderWidget2x2::class.java))
                for (appWidgetId in ids2x2) {
                    val opacity = WidgetConfigStore.getWidgetOpacity(context, appWidgetId)
                    val configuredId = WidgetConfigStore.get1x2Or2x2Config(context, appWidgetId)
                    update2x2WidgetWithData(context, appWidgetManager, appWidgetId, opacity, configuredId, reminders)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val ids4x2 = appWidgetManager.getAppWidgetIds(ComponentName(context, ReminderWidget4x2::class.java))
        if (ids4x2.isNotEmpty()) {
            val intent = Intent(context, ReminderWidget4x2::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids4x2)
            }
            context.sendBroadcast(intent)
            appWidgetManager.notifyAppWidgetViewDataChanged(ids4x2, R.id.widget_list_view)
        }
    }

    fun update1x2WidgetWithData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        opacity: Int,
        selectedId: Int,
        items: List<ReminderItem>
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_1x2)

        // 应用自定义主题（背景类型、颜色、图片等）
        applyCustomTheme(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            imageBgViewId = R.id.widget_1x2_image_bg,
            overlayViewId = R.id.widget_1x2_overlay,
            bgShapeViewId = R.id.widget_1x2_bg,
            contentViewId = R.id.widget_1x2_content,
            accentColorDefaultResId = R.color.widget_accent_annual
        )

        val featured = if (selectedId != -1) {
            items.find { it.id == selectedId } ?: getFeaturedReminder(items)
        } else {
            getFeaturedReminder(items)
        }

        if (featured != null) {
            val displayInfo = getDisplayInfo(context, featured)

            // 自定义标题
            val customTitle = WidgetConfigStore.getCustomTitle(context, appWidgetId)
            val titleText = if (customTitle.isNotEmpty()) customTitle else displayInfo.title
            views.setTextViewText(R.id.widget_1x2_title, titleText)

            // 是否显示标签行
            val showLabel = WidgetConfigStore.getShowLabel(context, appWidgetId)
            if (showLabel) {
                views.setViewVisibility(R.id.widget_1x2_label, View.VISIBLE)
                views.setTextViewText(R.id.widget_1x2_label, displayInfo.label)
            } else {
                views.setViewVisibility(R.id.widget_1x2_label, View.GONE)
            }

            views.setTextViewText(R.id.widget_1x2_days, displayInfo.days)
            views.setTextViewText(R.id.widget_1x2_unit, displayInfo.unit)

            // 应用响应式字号（1x2 专用）
            val (cellWidth, cellHeight) = getWidgetCellSize(appWidgetManager, appWidgetId)
            val responsiveTextSize = getResponsiveDaysTextSize1x2(context, displayInfo.days, displayInfo.unit, cellWidth, cellHeight)
            views.setFloat(R.id.widget_1x2_days, "setTextSize", responsiveTextSize)
            val unitTextSize = responsiveTextSize * 0.32f
            views.setFloat(R.id.widget_1x2_unit, "setTextSize", unitTextSize)

            // 自定义强调色
            val accentColor = resolveAccentColorInt(context, appWidgetId, displayInfo.accentColorResId)
            views.setTextColor(R.id.widget_1x2_days, accentColor)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("reminderId", featured.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                featured.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_1x2_container, pendingIntent)
        } else {
            views.setTextViewText(R.id.widget_1x2_title, "暂无日程")
            views.setTextViewText(R.id.widget_1x2_label, "点击添加")
            views.setTextViewText(R.id.widget_1x2_days, "0")
            views.setTextViewText(R.id.widget_1x2_unit, "天")

            views.setTextColor(R.id.widget_1x2_days, context.getColor(R.color.widget_accent_annual))

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_1x2_container, pendingIntent)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    fun update2x2WidgetWithData(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        opacity: Int,
        selectedId: Int,
        items: List<ReminderItem>
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout_2x2)

        // 应用自定义主题（背景类型、颜色、图片等）
        applyCustomTheme(
            context = context,
            views = views,
            appWidgetId = appWidgetId,
            imageBgViewId = R.id.widget_2x2_image_bg,
            overlayViewId = R.id.widget_2x2_overlay,
            bgShapeViewId = R.id.widget_2x2_bg,
            contentViewId = R.id.widget_2x2_content,
            accentColorDefaultResId = R.color.widget_accent_annual
        )

        val featured = if (selectedId != -1) {
            items.find { it.id == selectedId } ?: getFeaturedReminder(items)
        } else {
            getFeaturedReminder(items)
        }

        if (featured != null) {
            val displayInfo = getDisplayInfo(context, featured)

            // 自定义标题
            val customTitle = WidgetConfigStore.getCustomTitle(context, appWidgetId)
            val titleText = if (customTitle.isNotEmpty()) customTitle else displayInfo.title

            // 是否显示标签
            val showLabel = WidgetConfigStore.getShowLabel(context, appWidgetId)
            val headerText = if (showLabel) "$titleText ${displayInfo.label}" else titleText
            views.setTextViewText(R.id.widget_2x2_header_title, headerText)

            views.setTextViewText(R.id.widget_2x2_days, displayInfo.days)
            views.setTextViewText(R.id.widget_2x2_unit, displayInfo.unit)

            // 是否显示日期
            val showDate = WidgetConfigStore.getShowDate(context, appWidgetId)
            if (showDate) {
                views.setViewVisibility(R.id.widget_2x2_date, View.VISIBLE)
                views.setTextViewText(R.id.widget_2x2_date, displayInfo.dateString)
            } else {
                views.setViewVisibility(R.id.widget_2x2_date, View.GONE)
            }

            // 应用响应式字号（同时考虑数字和单位）
            val (cellWidth, cellHeight) = getWidgetCellSize(appWidgetManager, appWidgetId)
            val responsiveTextSize = getResponsiveDaysTextSize(context, displayInfo.days, displayInfo.unit, cellWidth, cellHeight)
            views.setFloat(R.id.widget_2x2_days, "setTextSize", responsiveTextSize)
            // 单位字号按比例缩放
            val unitTextSize = responsiveTextSize * 0.32f
            views.setFloat(R.id.widget_2x2_unit, "setTextSize", unitTextSize)

            // 自定义强调色
            val accentColor = resolveAccentColorInt(context, appWidgetId, displayInfo.accentColorResId)
            views.setInt(R.id.widget_2x2_header_bg, "setColorFilter", accentColor)
            views.setTextColor(R.id.widget_2x2_days, accentColor)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("reminderId", featured.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                featured.id + 10000,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_2x2_container, pendingIntent)
        } else {
            views.setTextViewText(R.id.widget_2x2_header_title, "暂无日程")
            views.setTextViewText(R.id.widget_2x2_days, "0")
            views.setTextViewText(R.id.widget_2x2_unit, "天")
            views.setTextViewText(R.id.widget_2x2_date, "——")

            views.setInt(R.id.widget_2x2_header_bg, "setColorFilter", context.getColor(R.color.widget_accent_annual))
            views.setTextColor(R.id.widget_2x2_days, context.getColor(R.color.widget_accent_annual))

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_2x2_container, pendingIntent)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}

object WidgetConfigStore {
    private const val PREFS_NAME = "com.ybhgl.reminder.widget_prefs"

    // ===== 背景类型常量 =====
    const val BG_TYPE_DEFAULT = "default"     // 跟随系统默认卡片背景
    const val BG_TYPE_COLOR = "color"         // 纯色背景
    const val BG_TYPE_IMAGE = "image"         // 图片背景

    // ===== 自定义主题相关 Key =====
    fun save1x2Or2x2Config(context: Context, appWidgetId: Int, reminderId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("widget_${appWidgetId}_reminder_id", reminderId)
            .commit()
    }

    /** 背景类型：default / color / image */
    fun saveBackgroundType(context: Context, appWidgetId: Int, bgType: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_bg_type", bgType)
            .commit()
    }

    fun getBackgroundType(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_bg_type", BG_TYPE_DEFAULT) ?: BG_TYPE_DEFAULT
    }

    /** 自定义背景色（HEX，仅当背景类型为 color 时生效） */
    fun saveBackgroundColor(context: Context, appWidgetId: Int, colorHex: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_bg_color", colorHex)
            .commit()
    }

    fun getBackgroundColor(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_bg_color", "") ?: ""
    }

    /** 背景图片路径列表（逗号分隔，仅当背景类型为 image 时生效） */
    fun saveBackgroundImagePaths(context: Context, appWidgetId: Int, paths: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_bg_images", paths.joinToString(","))
            .commit()
    }

    fun getBackgroundImagePaths(context: Context, appWidgetId: Int): List<String> {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_bg_images", "") ?: ""
        if (str.isEmpty()) return emptyList()
        return str.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }
    }

    /** 自定义强调色（HEX，空 = 跟随提醒类型默认色） */
    fun saveAccentColor(context: Context, appWidgetId: Int, colorHex: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_accent_color", colorHex)
            .commit()
    }

    fun getAccentColor(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_accent_color", "") ?: ""
    }

    /** 自定义标题覆盖（空 = 使用提醒标题） */
    fun saveCustomTitle(context: Context, appWidgetId: Int, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_custom_title", title)
            .commit()
    }

    fun getCustomTitle(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_custom_title", "") ?: ""
    }

    /** 是否显示日期行（2x2 footer / 4x2 item 日期） */
    fun saveShowDate(context: Context, appWidgetId: Int, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("widget_${appWidgetId}_show_date", show)
            .commit()
    }

    fun getShowDate(context: Context, appWidgetId: Int): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("widget_${appWidgetId}_show_date", true)
    }

    /** 是否显示标签行（"还有/第/生日" 等） */
    fun saveShowLabel(context: Context, appWidgetId: Int, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("widget_${appWidgetId}_show_label", show)
            .commit()
    }

    fun getShowLabel(context: Context, appWidgetId: Int): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("widget_${appWidgetId}_show_label", true)
    }

    fun get1x2Or2x2Config(context: Context, appWidgetId: Int): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("widget_${appWidgetId}_reminder_id", -1)
    }

    fun save4x2Config(context: Context, appWidgetId: Int, filterType: String, customIds: Set<Int>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("widget_${appWidgetId}_filter_type", filterType)
            .putString("widget_${appWidgetId}_custom_ids", customIds.joinToString(","))
            .commit()
    }

    fun get4x2FilterType(context: Context, appWidgetId: Int): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_filter_type", "all") ?: "all"
    }

    fun get4x2CustomIds(context: Context, appWidgetId: Int): Set<Int> {
        val str = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("widget_${appWidgetId}_custom_ids", "") ?: ""
        if (str.isEmpty()) return emptySet()
        return str.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun deleteConfig(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("widget_${appWidgetId}_reminder_id")
            .remove("widget_${appWidgetId}_filter_type")
            .remove("widget_${appWidgetId}_custom_ids")
            .remove("widget_${appWidgetId}_opacity")
            .remove("widget_${appWidgetId}_bg_type")
            .remove("widget_${appWidgetId}_bg_color")
            .remove("widget_${appWidgetId}_bg_images")
            .remove("widget_${appWidgetId}_accent_color")
            .remove("widget_${appWidgetId}_custom_title")
            .remove("widget_${appWidgetId}_show_date")
            .remove("widget_${appWidgetId}_show_label")
            .commit()
    }

    fun saveWidgetOpacity(context: Context, appWidgetId: Int, opacity: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("widget_${appWidgetId}_opacity", opacity)
            .commit()
    }

    fun getWidgetOpacity(context: Context, appWidgetId: Int): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("widget_${appWidgetId}_opacity", 100)
    }
}
