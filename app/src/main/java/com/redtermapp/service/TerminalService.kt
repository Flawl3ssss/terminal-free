package com.redtermapp.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.redtermapp.RedTermApp
import com.redtermapp.ui.TerminalActivity
import com.redtermapp.ui.TerminalViewModel
import java.io.File

/**
 * Session lifecycle service with two modes:
 *
 *  - SILENT: a plain started service with no notification, no wakelock and no
 *    polling. It exists only so [onTaskRemoved] can perform a full clean
 *    shutdown (sessions + proot + app process) when the user swipes the app
 *    away. This is the default mode.
 *
 *  - FOREGROUND: started via startForegroundService with a low-priority
 *    notification, optional wakelock and CPU polling. Used only when the user
 *    explicitly opts in to background operation (keep_alive) or enables the
 *    wakelock. START_STICKY applies only in this mode.
 */
class TerminalService : Service() {

    companion object {
        const val ACTION_START_FG = "com.terminalfree.action.START_FG"
        const val ACTION_START_SILENT = "com.terminalfree.action.START_SILENT"
        const val ACTION_DEMOTE = "com.terminalfree.action.DEMOTE"
        const val ACTION_ACQUIRE = "com.terminalfree.action.ACQUIRE_WAKELOCK"
        const val ACTION_RELEASE = "com.terminalfree.action.RELEASE_WAKELOCK"
        const val ACTION_EXIT = "com.terminalfree.action.EXIT"

        @Volatile private var running = false
        @Volatile private var foreground = false

        private fun wantForeground(context: Context): Boolean {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val wants = prefs.getBoolean("keep_alive", false) || prefs.getBoolean("wakelock", false)
            if (!wants) return false
            // Without the notification permission a foreground service would be
            // invisible and confusing - fall back to silent mode instead.
            return Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        /** Move the service into the mode required by the current settings. */
        fun ensure(context: Context) {
            val app = context.applicationContext
            val wantFg = wantForeground(app)
            val intent = Intent(app, TerminalService::class.java)
            when {
                !running -> {
                    intent.action = if (wantFg) ACTION_START_FG else ACTION_START_SILENT
                    if (wantFg) app.startForegroundService(intent) else app.startService(intent)
                }
                wantFg && !foreground -> {
                    intent.action = ACTION_START_FG
                    app.startForegroundService(intent)
                }
                !wantFg && foreground -> {
                    intent.action = ACTION_DEMOTE
                    app.startService(intent)
                }
                // Already in the desired state.
            }
        }

        /** Stop the service entirely (no sessions left / explicit exit). */
        fun stop(context: Context) {
            context.stopService(Intent(context.applicationContext, TerminalService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var polling = false
    private val lastCpuTicks = HashMap<Int, Long>()
    private var cpuPct = 0
    private val cpuHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cpuRunnable = object : Runnable {
        override fun run() {
            updateCpuLoad()
            cpuHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantFg = wantForeground(this)
        when (intent?.action) {
            ACTION_START_FG -> becomeForeground()
            ACTION_START_SILENT -> { /* Silent mode: nothing to show or poll. */ }
            ACTION_DEMOTE -> demote()
            ACTION_ACQUIRE -> {
                if (foreground) {
                    acquireWakeLock()
                    updateNotification()
                }
            }
            ACTION_RELEASE -> {
                releaseWakeLock()
                updateNotification()
            }
            ACTION_EXIT -> exitFully()
            null -> {
                // Sticky restart after process death: restore foreground mode
                // only if the user still wants background operation.
                if (wantFg) becomeForeground()
            }
        }
        // Resurrect only for an explicitly requested background mode.
        return if (wantFg) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val keepAlive = prefs.getBoolean("keep_alive", false)
        val killOnClose = prefs.getBoolean("kill_on_close", true)
        if (!keepAlive && killOnClose) {
            // Default behaviour: swiping the app from Recents terminates every
            // session, the service and the whole process group - nothing
            // lingers in memory afterwards.
            exitFully()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        polling = false
        cpuHandler.removeCallbacks(cpuRunnable)
        releaseWakeLock()
        running = false
        foreground = false
        super.onDestroy()
    }

    private fun becomeForeground() {
        if (!foreground) {
            startForeground(RedTermApp.NOTIF_ID_TERMINAL, makeNotification())
            foreground = true
        }
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("wakelock", false)) acquireWakeLock() else releaseWakeLock()
        if (!polling) {
            polling = true
            cpuHandler.postDelayed(cpuRunnable, 1000L)
        }
        updateNotification()
    }

    private fun demote() {
        polling = false
        cpuHandler.removeCallbacks(cpuRunnable)
        releaseWakeLock()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    private fun exitFully() {
        try {
            TerminalViewModel.get(application).clearSessions()
        } catch (_: Exception) {
        }
        polling = false
        cpuHandler.removeCallbacks(cpuRunnable)
        releaseWakeLock()
        if (foreground) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            foreground = false
        }
        stopSelf()
        hardKill()
    }

    private fun hardKill() {
        // Kill the whole process group so proot and every process inside the
        // container die together with the app - nothing is left behind.
        // Reflection is used deliberately: Os.getpgid / Process.killProcessGroup
        // are not part of the public SDK surface on all API levels.
        try {
            val uid = Process.myUid()
            var pgid = Process.myPid()
            try {
                val getpgid = Class.forName("android.system.Os")
                    .getMethod("getpgid", Integer.TYPE)
                pgid = getpgid.invoke(null, 0) as Int
            } catch (_: Exception) {
            }
            try {
                val killGroup = Class.forName("android.os.Process")
                    .getMethod("killProcessGroup", Integer.TYPE, Integer.TYPE)
                killGroup.invoke(null, uid, pgid)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        Process.killProcess(Process.myPid())
    }

    private fun updateCpuLoad() {
        val sessions = TerminalViewModel.get(application).sessions.value
        val pids = mutableListOf<Int>()
        for (s in sessions) {
            val pid = s.pid
            if (pid > 0) {
                pids.add(pid)
                collectChildren(pid, pids)
            }
        }
        val current = HashMap<Int, Long>()
        for (pid in pids) {
            current[pid] = readCpuTicks(pid)
        }
        val now = current.values.sum()
        val prev = lastCpuTicks.values.sum()
        val delta = (now - prev).coerceAtLeast(0)
        lastCpuTicks.clear()
        lastCpuTicks.putAll(current)
        val pct = (delta / 2).toInt().coerceIn(0, 400)
        if (pct != cpuPct) {
            cpuPct = pct
            updateNotification()
        }
    }

    private fun collectChildren(pid: Int, out: MutableList<Int>) {
        try {
            val childrenFile = File("/proc/$pid/task/$pid/children")
            if (!childrenFile.exists()) return
            for (child in childrenFile.readText().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val childPid = child.toIntOrNull() ?: continue
                out.add(childPid)
                collectChildren(childPid, out)
            }
        } catch (_: Exception) {
        }
    }

    private fun readCpuTicks(pid: Int): Long {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val after = stat.substringAfterLast(")")
            val parts = after.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 15) 0L else (parts[11].toLongOrNull() ?: 0L) + (parts[12].toLongOrNull() ?: 0L)
        } catch (_: Exception) {
            0L
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TerminalFree:TerminalWakeLock"
        ).apply { acquire(30 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun updateNotification() {
        if (!foreground) return
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(RedTermApp.NOTIF_ID_TERMINAL, makeNotification())
    }

    private fun makeNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, TerminalActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isHeld = wakeLock?.isHeld == true
        val wakelockStatus = if (isHeld) "\u25CF" else "\u25CB"

        val builder = NotificationCompat.Builder(this, RedTermApp.CHANNEL_TERMINAL)
            .setContentTitle("Terminal Free \u2014 Ubuntu")
            .setContentText("CPU $cpuPct% | $wakelockStatus Wake lock | Tap to open")
            .setSmallIcon(com.redtermapp.R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isHeld) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null, "Release", servicePendingIntent(ACTION_RELEASE, 1)
                ).build()
            )
        } else {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    null, "Acquire", servicePendingIntent(ACTION_ACQUIRE, 2)
                ).build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(
                null, "Exit", servicePendingIntent(ACTION_EXIT, 3)
            ).build()
        )
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val i = Intent(this, TerminalService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, requestCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getDistroName(): String {
        val dir = File(filesDir, "installed")
        return dir.list()?.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: "Terminal"
    }
}
