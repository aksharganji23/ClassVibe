package com.classvibe.app.notifications

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.classvibe.app.messages.MessageBank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 👍 / 👎 straight from a reminder notification. 👎 messages are no longer picked. */
class RateActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val rating = intent.getIntExtra(EXTRA_RATING, 0)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                MessageBank.rate(context.applicationContext, id, rating)
            } finally {
                pending.finish()
            }
        }
        Toast.makeText(context, if (rating > 0) "👍 More like this!" else "👎 Got it – you won't see that one again", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val ACTION = "com.classvibe.app.ACTION_RATE"
        private const val EXTRA_ID = "message_id"
        private const val EXTRA_RATING = "rating"

        fun intent(context: Context, messageId: String, rating: Int): PendingIntent {
            val i = Intent(context, RateActionReceiver::class.java)
                .setAction(ACTION)
                .setData(Uri.parse("classvibe://rate/$messageId/$rating"))
                .putExtra(EXTRA_ID, messageId)
                .putExtra(EXTRA_RATING, rating)
            return PendingIntent.getBroadcast(context, (messageId.hashCode() * 3 + rating), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun actions(context: Context, messageId: String?): List<NotificationHelper.Action> =
            if (messageId == null) emptyList() else listOf(
                NotificationHelper.Action("👍", intent(context, messageId, 1)),
                NotificationHelper.Action("👎", intent(context, messageId, -1))
            )
    }
}
