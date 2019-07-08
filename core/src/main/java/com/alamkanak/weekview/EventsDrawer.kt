package com.alamkanak.weekview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout.Alignment.ALIGN_NORMAL
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextUtils.TruncateAt.END
import android.text.TextUtils.ellipsize
import android.text.style.StyleSpan
import android.util.Pair
import com.alamkanak.weekview.WeekViewEvent.TextResource
import java.util.ArrayList
import java.util.Calendar

internal class EventsDrawer<T>(
    private val view: WeekView<T>,
    private val config: WeekViewConfigWrapper,
    private val cache: WeekViewCache<T>
) {

    private val context = view.context
    private val rectCalculator = EventChipRectCalculator<T>(config)
    private val staticLayoutCache = ArrayList<Pair<EventChip<T>, StaticLayout>>()

    fun drawSingleEvents(
        drawingContext: DrawingContext,
        canvas: Canvas,
        paint: Paint
    ) {
        drawingContext
            .dateRangeWithStartPixels
            .forEach { (date, startPixel) ->
                drawEventsForDate(date, startPixel, canvas, paint)
            }
    }

    private fun drawEventsForDate(
        date: Calendar,
        startPixel: Float,
        canvas: Canvas,
        paint: Paint
    ) {
        cache.normalEventChipsByDate(date)
            .filter { it.event.isWithin(config.minHour, config.maxHour) }
            .forEach {
                val chipRect = rectCalculator.calculateSingleEvent(it, startPixel)
                if (chipRect.isValidSingleEventRect) {
                    it.rect = chipRect
                    it.draw(context, config, canvas, paint)
                } else {
                    it.rect = null
                }
            }
    }

    /**
     * Compute the StaticLayout for all-day events to update the header height
     *
     * @param drawingContext The [DrawingContext] to use for drawing
     * @return The association of [EventChip]s with his StaticLayout
     */
    fun prepareDrawAllDayEvents(
        drawingContext: DrawingContext
    ): List<Pair<EventChip<T>, StaticLayout>> {
        config.setCurrentAllDayEventHeight(0)
        staticLayoutCache.clear()

        drawingContext
            .dateRangeWithStartPixels
            .forEach { (date, startPixel) ->
                val eventChips = cache.allDayEventChipsByDate(date)

                for (eventChip in eventChips) {
                    val layout = calculateLayoutForAllDayEvent(eventChip, startPixel)
                    if (layout != null) {
                        staticLayoutCache.add(Pair(eventChip, layout))
                    }
                }
            }

        return staticLayoutCache
    }

    private fun calculateLayoutForAllDayEvent(
        eventChip: EventChip<T>,
        startPixel: Float
    ): StaticLayout? {
        val chipRect = rectCalculator.calculateAllDayEvent(eventChip, startPixel)
        if (chipRect.isValidAllDayEventRect) {
            eventChip.rect = chipRect
            return calculateChipTextLayout(eventChip)
        } else {
            eventChip.rect = null
        }
        return null
    }

    private fun calculateChipTextLayout(
        eventChip: EventChip<T>
    ): StaticLayout? {
        val event = eventChip.event
        val rect = checkNotNull(eventChip.rect)

        val left = rect.left
        val top = rect.top
        val right = rect.right
        val bottom = rect.bottom

        val width = right - left - (config.eventPadding * 2)
        val height = bottom - top - (config.eventPadding * 2)

        if (height < 0f) {
            return null
        }

        if (width < 0f) {
            // This is needed if there are many all-day events
            val dummyTextLayout = createDummyTextLayout(event)
            val chipHeight = dummyTextLayout.height + config.eventPadding * 2
            rect.bottom = rect.top + chipHeight
            setAllDayEventHeight(chipHeight)
            return dummyTextLayout
        }

        val title = when (val resource = event.titleResource) {
            is TextResource.Id -> context.getString(resource.resId)
            is TextResource.Value -> resource.text
            null -> ""
        }

        val text = SpannableStringBuilder(title)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, text.length, 0)

        val location = when (val resource = event.locationResource) {
            is TextResource.Id -> context.getString(resource.resId)
            is TextResource.Value -> resource.text
            null -> null
        }

        location?.let {
            text.append(' ')
            text.append(it)
        }

        val availableWidth = width.toInt()

        // Get text dimensions.
        val textPaint = event.getTextPaint(context, config)
        val textLayout = StaticLayout(text, textPaint, availableWidth, ALIGN_NORMAL, 1f, 0f, false)

        val lineHeight = textLayout.height / textLayout.lineCount

        // For an all day event, we display just one line
        val chipHeight = lineHeight + config.eventPadding * 2
        rect.bottom = rect.top + chipHeight

        // Compute the available height on the right size of the chip
        val availableHeight = (rect.bottom - top - (config.eventPadding * 2).toFloat()).toInt()

        val finalTextLayout = if (availableHeight >= lineHeight) {
            ellipsizeTextToFitChip(eventChip, text, textLayout, config, availableHeight, availableWidth)
        } else {
            textLayout
        }

        // Refresh the header height
        setAllDayEventHeight(chipHeight)

        return finalTextLayout
    }

    private fun setAllDayEventHeight(height: Int) {
        if (height > config.getCurrentAllDayEventHeight()) {
            config.setCurrentAllDayEventHeight(height)
        }
    }

    /**
     * Creates a dummy text layout that is only used to determine the height of all-day events.
     */
    private fun createDummyTextLayout(
        event: WeekViewEvent<T>
    ): StaticLayout {
        val textPaint = event.getTextPaint(context, config)
        return StaticLayout("", textPaint, 0, ALIGN_NORMAL, 1f, 0f, false)
    }

    private fun ellipsizeTextToFitChip(
        eventChip: EventChip<T>,
        text: CharSequence,
        staticLayout: StaticLayout,
        config: WeekViewConfigWrapper,
        availableHeight: Int,
        availableWidth: Int
    ): StaticLayout {
        var textLayout = staticLayout
        val textPaint = eventChip.event.getTextPaint(context, config)

        val lineHeight = textLayout.lineHeight
        var availableLineCount = availableHeight / lineHeight

        val rect = checkNotNull(eventChip.rect)
        val left = rect.left
        val right = rect.right

        do {
            // Ellipsize text to fit into event rect.
            val availableArea = availableLineCount * availableWidth
            val ellipsized = ellipsize(text, textPaint, availableArea.toFloat(), END)
            val width = (right - left - (config.eventPadding * 2).toFloat()).toInt()

            if (eventChip.event.isAllDay && width < 0) {
                // This day contains too many all-day events. We only draw the event chips,
                // but don't attempt to draw the event titles.
                break
            }

            textLayout = StaticLayout(ellipsized, textPaint, width, ALIGN_NORMAL, 1f, 0f, false)

            // Reduce line count.
            availableLineCount--

            // Repeat until text is short enough.
        } while (textLayout.height > availableHeight)

        return textLayout
    }

    /**
     * Draw all the all-day events of a particular day.
     *
     * @param eventChips The list of Pair<[EventChip], StaticLayout>s to draw
     * @param canvas         The canvas to draw upon.
     */
    fun drawAllDayEvents(
        eventChips: List<Pair<EventChip<T>, StaticLayout>>?,
        canvas: Canvas,
        paint: Paint
    ) {
        if (eventChips == null) {
            return
        }

        for (pair in eventChips) {
            val eventChip = pair.first
            val layout = pair.second
            eventChip.draw(context, config, layout, canvas, paint)
        }

        // Hide events when they are in the top left corner
        val headerBackground = config.headerBackgroundPaint

        val headerRowBottomLine = if (config.showHeaderRowBottomLine) {
            config.headerRowBottomLinePaint.strokeWidth
        } else {
            0f
        }

        val height = config.headerHeight - headerRowBottomLine * 1.5f
        val width = config.timeTextWidth + config.timeColumnPadding * 2

        canvas.clipRect(0f, 0f, width, height)
        canvas.drawRect(0f, 0f, width, height, headerBackground)

        canvas.restore()
        canvas.save()
    }

    private val RectF.isValidSingleEventRect: Boolean
        get() = (left < right
            && left < view.width
            && top < view.height
            && right > config.timeColumnWidth
            && bottom > config.headerHeight)

    private val RectF.isValidAllDayEventRect: Boolean
        get() = (left < right
            && left < view.width
            && top < view.height
            && right > config.timeColumnWidth
            && bottom > 0)

}