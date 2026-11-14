package com.classvibe.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.classvibe.app.data.ThemeMode
import com.classvibe.app.ui.theme.Brand
import com.classvibe.app.ui.theme.BrandLight
import com.classvibe.app.ui.theme.Orange
import com.classvibe.app.ui.theme.OrangeBright

/** First launch: choose Light / Dark / Follow phone with a live mini preview. */
@Composable
fun OnboardingScreen(onDone: (ThemeMode) -> Unit) {
    var choice by remember { mutableStateOf(ThemeMode.LIGHT) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("👋 Welcome to ClassVibe", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your offline timetable with reminders that actually make you laugh.\nFirst, choose your look:",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeOption("☀️ Light", ThemeMode.LIGHT, choice, dark = false, Modifier.weight(1f)) { choice = it }
            ThemeOption("🌙 Dark", ThemeMode.DARK, choice, dark = true, Modifier.weight(1f)) { choice = it }
        }
        Spacer(Modifier.height(12.dp))
        Card(
            onClick = { choice = ThemeMode.SYSTEM },
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(if (choice == ThemeMode.SYSTEM) 2.dp else 1.dp, if (choice == ThemeMode.SYSTEM) Brand else MaterialTheme.colorScheme.outlineVariant),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📱 Follow phone setting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = { onDone(choice) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Let's go 🚀") }
        Spacer(Modifier.height(8.dp))
        Text("You can change this any time in Settings → Appearance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThemeOption(label: String, mode: ThemeMode, selected: ThemeMode, dark: Boolean, modifier: Modifier, onPick: (ThemeMode) -> Unit) {
    val bg = if (dark) Color.Black else Color.White
    val card = if (dark) Color(0xFF121418) else Color(0xFFF4F7FB)
    val text = if (dark) Color(0xFFF2F4F7) else Color(0xFF1A1C21)
    val primary = if (dark) BrandLight else Brand
    val accent = if (dark) OrangeBright else Orange
    Card(
        onClick = { onPick(mode) },
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(if (selected == mode) 3.dp else 1.dp, if (selected == mode) Brand else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Column(Modifier.padding(12.dp)) {
            // Mini preview
            Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(Brand)) {
                Text("ClassVibe", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(8.dp))
            repeat(3) { i ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(card).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = 4.dp, height = 18.dp).background(if (i == 1) accent else primary))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.height(8.dp).fillMaxWidth(0.7f).clip(RoundedCornerShape(4.dp)).background(text.copy(alpha = 0.6f)))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = text, style = MaterialTheme.typography.titleMedium)
        }
    }
}
