package com.classvibe.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.AttendanceStatus
import com.classvibe.app.data.ClassSchedule
import com.classvibe.app.data.ClassStatus
import com.classvibe.app.ui.theme.Cv
import com.classvibe.app.ui.theme.classTypeColor
import com.classvibe.app.ui.theme.courseColor

/**
 * Card for one class. [status] null = no live badge (e.g. weekly view for another day).
 * When [onMark] is given (Today screen) the card shows attendance buttons and a menu.
 */
@Composable
fun ClassCard(
    schedule: ClassSchedule,
    status: ClassStatus?,
    nowMinutes: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    attendance: AttendanceStatus? = null,
    onMark: ((AttendanceStatus?) -> Unit)? = null,
    onAddExam: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val accent = courseColor(schedule.course)
    val cancelled = attendance == AttendanceStatus.CANCELLED || status == ClassStatus.CANCELLED
    val effectiveStatus = if (cancelled) ClassStatus.CANCELLED else status
    val targetAlpha = if (effectiveStatus == ClassStatus.COMPLETED || cancelled) 0.6f else 1f
    val alpha by animateFloatAsState(targetAlpha, tween(400), label = "alpha")
    val borderColor by animateColorAsState(
        if (effectiveStatus == ClassStatus.IN_PROGRESS) accent else MaterialTheme.colorScheme.outlineVariant,
        label = "border"
    )
    val cardModifier = modifier.fillMaxWidth()
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val border = BorderStroke(if (effectiveStatus == ClassStatus.IN_PROGRESS) 1.5.dp else 1.dp, borderColor)
    val shape = RoundedCornerShape(20.dp)

    val content: @Composable () -> Unit = {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = if (compact) 10.dp else 14.dp, bottom = if (compact) 10.dp else 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(alpha)) {
                    TypeTag(schedule.classType)
                    Spacer(Modifier.width(8.dp))
                    Text(schedule.timeRangeText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (effectiveStatus != null) StatusBadge(effectiveStatus)
                    if (onMark != null || onAddExam != null) CardMenu(cancelled, onMark, onAddExam)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    schedule.course,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                    modifier = Modifier.alpha(alpha)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.alpha(alpha)) {
                    if (schedule.room.isNotBlank()) InfoChip(Icons.Default.Place, schedule.room, Modifier.weight(1f, fill = false))
                    if (!compact) InfoChip(Icons.Default.DateRange, schedule.dayName)
                    schedule.parityLabel?.let { Pill(it, Cv.colors.accent) }
                }
                if (schedule.instructor.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    InfoChip(Icons.Default.Person, schedule.instructor, Modifier.alpha(alpha))
                }
                if (effectiveStatus == ClassStatus.IN_PROGRESS) {
                    val total = (schedule.endMinutes - schedule.startMinutes).coerceAtLeast(1)
                    val fraction = ((nowMinutes - schedule.startMinutes).toFloat() / total).coerceIn(0f, 1f)
                    val animated by animateFloatAsState(fraction, tween(800), label = "progress")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(6.dp).clip(CircleShape),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${schedule.endMinutes - nowMinutes} min left", style = MaterialTheme.typography.labelSmall, color = accent)
                }
                // Attendance buttons once the class has started.
                if (onMark != null && !cancelled && (effectiveStatus == ClassStatus.IN_PROGRESS || effectiveStatus == ClassStatus.COMPLETED)) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = attendance == AttendanceStatus.ATTENDED,
                            onClick = { onMark(if (attendance == AttendanceStatus.ATTENDED) null else AttendanceStatus.ATTENDED) },
                            label = { Text("✅ Attended") }
                        )
                        FilterChip(
                            selected = attendance == AttendanceStatus.MISSED,
                            onClick = { onMark(if (attendance == AttendanceStatus.MISSED) null else AttendanceStatus.MISSED) },
                            label = { Text("❌ Missed") }
                        )
                    }
                }
            }
        }
    }

    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = shape, colors = colors, border = border) { content() }
    } else {
        Card(modifier = cardModifier, shape = shape, colors = colors, border = border) { content() }
    }
}

@Composable
private fun CardMenu(cancelled: Boolean, onMark: ((AttendanceStatus?) -> Unit)?, onAddExam: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onMark != null) {
                if (cancelled) {
                    DropdownMenuItem(text = { Text("↩️ Undo cancel") }, onClick = { onMark(null); open = false })
                } else {
                    DropdownMenuItem(text = { Text("🚫 Class cancelled today") }, onClick = { onMark(AttendanceStatus.CANCELLED); open = false })
                }
            }
            if (onAddExam != null) {
                DropdownMenuItem(text = { Text("📝 Add exam for this subject") }, onClick = { onAddExam(); open = false })
            }
        }
    }
}

@Composable
fun TypeTag(type: String) = Pill(type.ifBlank { "Class" }.uppercase(), classTypeColor(type))

@Composable
fun StatusBadge(status: ClassStatus) {
    val cv = Cv.colors
    val color = when (status) {
        ClassStatus.UPCOMING -> cv.brandText
        ClassStatus.IN_PROGRESS -> cv.accent
        ClassStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
        ClassStatus.CANCELLED -> cv.danger
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        if (status == ClassStatus.IN_PROGRESS) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val dotAlpha by pulse.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "dot"
            )
            Box(Modifier.size(7.dp).alpha(dotAlpha).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(status.label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InfoChip(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
