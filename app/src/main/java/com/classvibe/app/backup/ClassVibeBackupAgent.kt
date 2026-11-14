package com.classvibe.app.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor

/**
 * Android's automatic cloud backup, but only when the user allows it
 * (Settings → Backup & sharing → "Phone cloud backup").
 */
class ClassVibeBackupAgent : BackupAgent() {

    override fun onFullBackup(data: FullBackupDataOutput) {
        if (isEnabled(this)) super.onFullBackup(data)
    }

    // Key/value backup isn't used (fullBackupOnly = true).
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) {}
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) {}

    companion object {
        private const val PREFS = "cv_backup"
        private const val KEY = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        }
    }
}
