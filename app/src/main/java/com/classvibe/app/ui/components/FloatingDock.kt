package com.classvibe.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classvibe.app.ui.theme.Cv

/** Extra bottom space screens add so their last item can scroll above the floating dock. */
val LocalDockPadding = compositionLocalOf { 0.dp }

/** Space the dock takes (height + margin + breathing room); screens add this as bottom content padding. */
val DOCK_SPACE: Dp = 128.dp

private val DOCK_H = 68.dp
private val INNER = 6.dp

data class DockItem(val label: String, val icon: ImageVector)

/**
 * iPhone-style floating glass dock.
 * @param position continuous page position (current page + swipe offset) so the glowing pill follows your finger
 * @param transparency 0 = solid … 85 = very see-through
 */
@Composable
fun FloatingDock(
    items: List<DockItem>,
    position: Float,
    selected: Int,
    transparency: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme() || MaterialTheme.colorScheme.background.luminanceLow()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val alpha = (1f - transparency / 100f).coerceIn(0.15f, 1f)
    val shape = RoundedCornerShape(30.dp)
    val brand = Cv.colors.brandText
    val haptic = LocalHapticFeedback.current
    // 4 tabs → roomy; 5 tabs (with Training) → a little narrower so it fits every phone.
    val ITEM_W = if (items.size > 4) 66.dp else 76.dp
    Box(
        modifier
            .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        base.copy(alpha = (alpha + 0.08f).coerceAtMost(1f)),
                        base.copy(alpha = alpha)
                    )
                )
            )
            // Glass edge highlight
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.22f else 0.65f), Color.White.copy(alpha = if (dark) 0.05f else 0.15f))
                ),
                shape
            )
            .height(DOCK_H)
            .width(ITEM_W * items.size + INNER * 2)
    ) {
        // Glowing pill that slides between tabs (and follows a swipe).
        Box(
            Modifier
                .offset(x = INNER + 2.dp + ITEM_W * position.coerceIn(0f, (items.size - 1).toFloat()), y = INNER)
                .size(ITEM_W - 4.dp, DOCK_H - INNER * 2)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(listOf(brand.copy(alpha = if (dark) 0.34f else 0.20f), brand.copy(alpha = if (dark) 0.22f else 0.12f)))
                )
        )
        Row(Modifier.fillMaxSize().padding(horizontal = INNER), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { i, item ->
                val isSel = i == selected
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    when {
                        pressed -> 0.86f
                        isSel -> 1.12f
                        else -> 1f
                    },
                    spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMediumLow),
                    label = "dockScale"
                )
                val tint by animateColorAsState(
                    if (isSel) brand else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(250), label = "dockTint"
                )
                Column(
                    Modifier
                        .width(ITEM_W)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(interactionSource = source, indication = null, role = Role.Tab) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp).scale(scale))
                    val labelAlpha by animateFloatAsState(if (isSel) 1f else 0f, tween(220), label = "dockLabel")
                    Text(
                        item.label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp).graphicsLayer { alpha = labelAlpha }
                    )
                }
            }
        }
    }
}

private fun Color.luminanceLow(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.4f

/** Small live preview of the dock for the Settings slider. */
@Composable
fun DockPreview(transparency: Int) {
    val brand = Cv.colors.brandText
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(brand, Cv.colors.accent, MaterialTheme.colorScheme.tertiary))),
        contentAlignment = Alignment.BottomCenter
    ) {
        Text(
            "Your timetable scrolls behind the dock ✨", color = Color.White, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp)
        )
        FloatingDock(
            items = listOf(
                DockItem("Today", Icons.Default.Home),
                DockItem("Timetable", Icons.Default.DateRange),
                DockItem("Exams", Icons.Default.Edit),
                DockItem("Stats", Icons.Default.Star)
            ),
            position = 0f, selected = 0, transparency = transparency, onSelect = {},
            modifier = Modifier.padding(bottom = 10.dp).scale(0.85f)
        )
    }
}
