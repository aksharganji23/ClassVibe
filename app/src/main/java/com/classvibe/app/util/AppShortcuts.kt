package com.classvibe.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.classvibe.app.MainActivity
import com.classvibe.app.R

/** Long-press the app icon: Next class • Today & attendance • Add exam • Bunk planner. */
object AppShortcuts {

    const val ACTION_NEXT_CLASS = "next_class"
    const val ACTION_ADD_EXAM = "add_exam"
    const val ACTION_BUNK = "bunk_planner"
    const val ACTION_TODAY = "today"

    fun publish(context: Context) {
        fun shortcut(id: String, label: String, longLabel: String, icon: Int, action: String, rank: Int) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(longLabel)
                .setIcon(IconCompat.createWithResource(context, icon))
                .setRank(rank)
                .setIntent(
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_ACTION, action)
                )
                .build()
        try {
            ShortcutManagerCompat.setDynamicShortcuts(
                context,
                listOf(
                    shortcut("next_class", "Next class", "🔔 Next class", R.drawable.ic_sc_next, ACTION_NEXT_CLASS, 0),
                    shortcut("today", "Today", "📅 Today & attendance", R.drawable.ic_sc_today, ACTION_TODAY, 1),
                    shortcut("add_exam", "Add exam", "📝 Add exam", R.drawable.ic_sc_exam, ACTION_ADD_EXAM, 2),
                    shortcut("bunk", "Bunk planner", "🧮 Bunk planner", R.drawable.ic_sc_bunk, ACTION_BUNK, 3)
                )
            )
        } catch (e: Exception) {
            // Some launchers don't support shortcuts – nothing to do.
        }
    }
}
