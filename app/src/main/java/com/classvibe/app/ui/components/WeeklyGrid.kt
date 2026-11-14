package com.classvibe.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.parser.DayParser
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.ui.theme.courseColor

/** Colour-coded Mon–Sun × time grid, like a printed timetable. Scrolls sideways. */
@Composable
fun WeeklyGrid(
    classes: List<ClassSchedule>,
    today: Int,
    nowMinutes: Int,
    modifier: Modifier = Modifier,
    onClick: ((ClassSchedule) -> Unit)? = null
) {
    val days = (1..7).filter { d -> classes.any { it.dayOfWeek == d } }.ifEmpty { (1..5).toList() }
    val startHour = (classes.minOfOrNull { it.startMinutes } ?: (9 * 60)) / 60
    val endHour = ((classes.maxOfOrNull { it.endMinutes } ?: (17 * 60)) + 59) / 60
    val hours = (startHour until endHour.coerceAtLeast(startHour + 1)).toList()
    val hourHeight = 72.dp
    val colWidth = 128.dp
    val headerHeight = 34.dp
    val totalHeight = hourHeight * hours.size

    Row(modifier.horizontalScroll(rememberScrollState())) {
        // Time labels
        Column(Modifier.width(48.dp)) {
            Spacer(Modifier.height(headerHeight))
            hours.forEach { h ->
                Box(Modifier.height(hourHeight)) {
                    Text("%02d:00".format(h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        days.forEach { day ->
            Column(Modifier.width(colWidth)) {
                Box(Modifier.height(headerHeight).fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(
                        DayParser.short(day) + if (day == today) " • today" else "",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (day == today) Cv.colors.brandText else MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(
                    Modifier.height(totalHeight).fillMaxWidth()
                        .background(if (day == today) Cv.colors.brandText.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface)
                ) {
                    hours.forEachIndexed { i, _ ->
                        HorizontalDivider(Modifier.offset(y = hourHeight * i), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (day == today && nowMinutes / 60 in hours) {
                        HorizontalDivider(
                            Modifier.offset(y = hourHeight * ((nowMinutes - startHour * 60) / 60f)),
                            thickness = 2.dp, color = Cv.colors.accent
                        )
                    }
                    classes.filter { it.dayOfWeek == day }.forEach { c ->
                        val color = courseColor(c.course)
                        val top = hourHeight * ((c.startMinutes - startHour * 60) / 60f)
                        val height = hourHeight * ((c.endMinutes - c.startMinutes) / 60f)
                        Box(
                            Modifier.offset(y = top).height(height).fillMaxWidth().padding(2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(color.copy(alpha = 0.16f))
                                .let { m -> if (onClick != null) m.clickable { onClick(c) } else m }
                        ) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(color))
                            Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp)) {
                                Text(c.course, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                                Text("${c.startTimeText} · ${c.classType}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                if (c.room.isNotBlank()) Text(c.room, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
