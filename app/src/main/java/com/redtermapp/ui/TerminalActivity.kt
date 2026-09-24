package com.redtermapp.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import androidx.core.view.GravityCompat
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import com.redtermapp.R
import com.redtermapp.distro.DistroInstaller
import com.redtermapp.distro.FsUtil
import com.redtermapp.harness.DshManager
import com.redtermapp.proot.ProotRunner
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import java.io.File

class TerminalActivity : AppCompatActivity() {

    private lateinit var distroName: String
    private var pendingStartDir: String? = null
    private lateinit var terminalView: TerminalView
    private lateinit var searchHighlight: SearchHighlightOverlay
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionListContainer: LinearLayout

    private var terminalBackend: TerminalBackend? = null
    private var currentFontSize = 20
    private var imeVisible = false
    private var suppressKeyboardUntil = 0L
    private var tapDownX = 0f
    private var tapDownY = 0f
    private var tapDownAt = 0L
    private lateinit var keyboardToggle: ImageButton

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var searchMatches = mutableListOf<SearchMatch>()
    private var searchIndex = -1

    companion object {
        const val EXTRA_DISTRO = "distro"
        const val EXTRA_START_DIR = "start_dir"

        fun launch(context: Context, distroName: String, startDir: String? = null) {
            context.startActivity(
                Intent(context, TerminalActivity::class.java).apply {
                    putExtra(EXTRA_DISTRO, distroName)
                    if (startDir != null) putExtra(EXTRA_START_DIR, startDir)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            )
        }
    }

    private val sessionModel: TerminalViewModel by lazy { TerminalViewModel.get(application) }
    private val sessions: List<TerminalSession> get() = sessionModel.sessions.value
    private val currentIndex: Int get() = sessionModel.currentIndex.value

    private val nightReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            recreate()
        }
    }

    private val titleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val titleRunnable = object : Runnable {
        override fun run() {
            updateCwdTitle()
            titleHandler.postDelayed(this, 1000)
        }
    }

    private fun wireBackend(backend: TerminalBackend) {
        backend.onSessionFinished = { finishedSession -> handleSessionFinished(finishedSession) }
        backend.onLinkTap = { link, isPath -> handleLinkTap(link, isPath) }
        backend.onModifiersChanged = {
            // The backend may auto-release a latch after a code point, so the
            // activity mirrors its state instead of keeping an independent one.
            val active = focusedBackend() ?: backend
            ctrlActive = active.isCtrlLatched
            altActive = active.isAltLatched
            updateModifierButtons()
        }
        backend.onRequestKeyboard = { target -> showKeyboard(target) }
        backend.imeVisibleProvider = { imeVisible }
    }

    private fun handleLinkTap(link: String, isPath: Boolean) {
        if (isPath) {
            copyText(link)
        } else {
            // A dialog is about to open: keep the keyboard from popping up
            // underneath it while the user picks an action.
            suppressKeyboardUntil = android.os.SystemClock.uptimeMillis() + 500
            android.app.AlertDialog.Builder(this)
                .setTitle(link)
                .setItems(arrayOf("Open in browser", "Copy link")) { _, which ->
                    when (which) {
                        0 -> {
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, (if (link.startsWith("http")) link else "https://$link").toUri()))
                            } catch (_: Exception) {
                                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> copyText(link)
                    }
                }
                .show()
        }
    }

    private fun copyText(text: String) {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        com.redtermapp.util.ScreenTabs.attach(this, R.id.tab_terminal)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        distroName = intent?.getStringExtra(EXTRA_DISTRO) ?: "ubuntu"
        pendingStartDir = intent?.getStringExtra(EXTRA_START_DIR)
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit { putString("last_distro", distroName) }
        terminalView = findViewById(R.id.terminal_view)
        searchHighlight = findViewById(R.id.search_highlight_overlay)
        searchHighlight.attachTerminalView(terminalView)
        drawerLayout = findViewById(R.id.drawer_layout)
        sessionListContainer = findViewById(R.id.session_list_container)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_back_chip)
        supportActionBar?.title = distroName.replaceFirstChar { it.uppercase() }

        setupExtraKeysRow1()
        setupExtraKeysRow2()
        setupSearchPanel()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        val sizeBytes = FsUtil.uniqueSize(rootfsDir)
        val sizeStr = when {
            sizeBytes < 1_000_000 -> "${sizeBytes / 1000} KB"
            sizeBytes < 1_000_000_000 -> "${"%.1f".format(sizeBytes / 1_000_000.0)} MB"
            else -> "${"%.2f".format(sizeBytes / 1_000_000_000.0)} GB"
        }
        findViewById<TextView>(R.id.distro_size_label).text = getString(R.string.distro_size_format, distroName, sizeStr)

        setupQuickPanel(prefs)
        if (prefs.getBoolean("autohide_keys", false)) {
            toggleExtraKeys(false)
        }

        keyboardToggle = findViewById(R.id.keyboard_toggle)
        keyboardToggle.setOnClickListener { toggleKeyboard() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            imeVisible = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom > 0
            updateKeyboardButton()
            insets
        }
        updateKeyboardButton()

        findViewById<TextView>(R.id.new_session_button).setOnClickListener {
            createNewSession()
        }

        findViewById<TextView>(R.id.export_btn).setOnClickListener {
            exportCurrentOutput()
        }

        findViewById<TextView>(R.id.copy_selected_btn).setOnClickListener {
            copySelectedText()
        }

        findViewById<TextView>(R.id.paste_btn).setOnClickListener {
            pasteClipboard()
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this, nightReceiver,
            android.content.IntentFilter(NightModeReceiver.ACTION_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (sessions.isEmpty()) {
            createNewSession()
        } else {
            val backend = TerminalBackend(terminalView, this).also {
                terminalBackend = it
                terminalView.setTerminalViewClient(it)
                wireBackend(it)
            }
            for (s in sessions) {
                s.updateTerminalSessionClient(backend)
            }
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            currentFontSize = prefs.getInt("font_size", 20)
            terminalView.setTextSize(currentFontSize)
            applyFontFromPrefs(prefs)
            terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            terminalView.post {
                terminalView.requestFocus()
                terminalView.isFocusableInTouchMode = true
            }
            val target = sessions.indexOfFirst { it.mSessionName.equals(distroName, ignoreCase = true) }
            if (target >= 0 && target != currentIndex) {
                sessionModel.switchToSession(target)
                terminalView.attachSession(sessions[target])
                terminalView.onScreenUpdated()
            }
            supportActionBar?.title = sessions[currentIndex].mSessionName.ifEmpty {
                distroName.replaceFirstChar { it.uppercase() }
            }
            updateDrawer()
        }
        startForegroundService()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun tc(attr: Int, default: Int): Int {
        val ta = theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, default)
        ta.recycle()
        return c
    }

    private val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var repeatAction: (() -> Unit)? = null

    private fun createKeyButton(label: String, action: () -> Unit): Button {
        val textColor = tc(R.attr.terminalText, 0xFFCDD6F4.toInt())
        val repeatable = label in listOf(
            "\u25B2", "UP", "\u25BC", "DOWN", "\u25C0", "LEFT", "\u25B6", "RIGHT",
            "\u232B", "BACKSPACE", "DEL", "INS"
        )
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            setBackgroundResource(0)
            setPadding(4, 4, 4, 4)
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply { weight = 1f; setMargins(2, 4, 2, 4); gravity = Gravity.CENTER }
            setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        setBackgroundColor(0xFF45475A.toInt())
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        if (repeatable) {
                            action()
                            startKeyRepeat(action)
                        }
                        // Consume the event here. Returning false let it fall
                        // through to View.onTouchEvent(), which fired
                        // performClick() a second time - so one tap on CTRL/ALT
                        // toggled the latch on and immediately back off, while
                        // ESC/TAB/&& were sent twice.
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        val released = event.action == android.view.MotionEvent.ACTION_UP
                        stopKeyRepeat()
                        if (!repeatable) {
                            // Clear the press flash BEFORE the click so a latched
                            // CTRL/ALT can repaint itself in updateModifierButtons().
                            setBackgroundColor(0)
                        }
                        if (released) v.performClick()
                        true
                    }
                    else -> true
                }
            }
            if (repeatable) {
                setOnClickListener { }
            } else {
                setOnClickListener { action() }
            }
        }
    }

    private fun startKeyRepeat(action: () -> Unit) {
        stopKeyRepeat()
        repeatAction = action
        repeatRunnable = object : Runnable {
            override fun run() {
                repeatAction?.invoke()
                repeatHandler.postDelayed(this, 50)
            }
        }
        repeatHandler.postDelayed(repeatRunnable!!, 400)
    }

    private fun stopKeyRepeat() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
        repeatAction = null
    }

    private fun extraKeyLabels(): Pair<List<String>, List<String>> {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val d1 = "\u2630 ESC TAB CTRL ALT \u25B2 HOME END \uFF0B"
        val d2 = "INS DEL && \u25C0 \u25BC \u25B6 \u232B"
        val split = { s: String -> s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() } }
        return split(prefs.getString("extra_keys_row1", d1)!!) to
            split(prefs.getString("extra_keys_row2", d2)!!)
    }

    private fun focusedTerminalView(): com.termux.view.TerminalView =
        focusedSplitView() ?: terminalView

    private fun focusedSession(): TerminalSession? =
        splitViewSession[focusedTerminalView()] ?: session

    private fun keyAction(label: String): () -> Unit {
        val actions: List<Pair<String, () -> Unit>> = listOf(
            "\u2630" to { drawerLayout.openDrawer(GravityCompat.START) },
            "MENU" to { drawerLayout.openDrawer(GravityCompat.START) },
            "ESC" to { focusedSession()?.writeCodePoint(false, 27); Unit },
            "TAB" to { focusedSession()?.writeCodePoint(false, 9); Unit },
            "CTRL" to { toggleCtrl() },
            "ALT" to { toggleAlt() },
            "＋" to { openWorkspacePicker(); Unit },
            "\u25B2" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "UP" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0); Unit },
            "\u25BC" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "DOWN" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0); Unit },
            "\u25C0" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "LEFT" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0); Unit },
            "\u25B6" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "RIGHT" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0); Unit },
            "HOME" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_MOVE_HOME, 0); Unit },
            "END" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_MOVE_END, 0); Unit },
            "INS" to { focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_INSERT, 0); Unit },
            "DEL" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_FORWARD_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_FORWARD_DEL, 0)
                Unit
            },
            "\u232B" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DEL, 0)
                Unit
            },
            "BACKSPACE" to {
                if (isSearchPanelVisible()) searchInputKey(KeyEvent.KEYCODE_DEL)
                else focusedTerminalView().handleKeyCode(KeyEvent.KEYCODE_DEL, 0)
                Unit
            },
            "&&" to { focusedSession()?.write("&&"); Unit },
        )
        return actions.firstOrNull { it.first == label }?.second
            ?: { focusedSession()?.write(label) }
    }

    private fun setupExtraKeysRow1() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container)
        for (label in extraKeyLabels().first) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    private fun setupExtraKeysRow2() {
        val container = findViewById<LinearLayout>(R.id.extra_keys_container_row2)
        for (label in extraKeyLabels().second) {
            container.addView(createKeyButton(label, keyAction(label)))
        }
    }

    // ------------------------------------------------------------------
    // Path picker ("＋" extra key): opens the FULL file manager screen
    // in pick mode; tapping a file there returns its path here, and it
    // gets typed into the focused session (quoted when it has spaces).
    // ------------------------------------------------------------------

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra(FilesActivity.EXTRA_RESULT_PATH)
            if (result.resultCode == RESULT_OK && !path.isNullOrEmpty()) {
                var typed = path
                if (typed.contains(' ')) typed = "'" + typed.replace("'", "'\\''") + "'"
                focusedSession()?.write(typed)
            }
        }

    private fun openWorkspacePicker() {
        pickFileLauncher.launch(FilesActivity.intent(this, true))
    }

    private var ctrlActive = false
    private var altActive = false

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        focusedBackend()?.setCtrl(ctrlActive)
        updateModifierButtons()
    }

    private fun toggleAlt() {
        altActive = !altActive
        focusedBackend()?.setAlt(altActive)
        updateModifierButtons()
    }

    private fun updateModifierButtons() {
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container)
        for (i in 0 until row1.childCount) {
            val btn = row1.getChildAt(i) as? Button ?: continue
            when (btn.text.toString()) {
                "CTRL" -> btn.setBackgroundColor(if (ctrlActive) 0xFF45475A.toInt() else 0)
                "ALT" -> btn.setBackgroundColor(if (altActive) 0xFF45475A.toInt() else 0)
            }
        }
    }

    private fun toggleExtraKeys(show: Boolean) {
        val vis = if (show) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<LinearLayout>(R.id.extra_keys_container).visibility = vis
        findViewById<LinearLayout>(R.id.extra_keys_container_row2).visibility = vis
    }

    private fun updateExtraKeysVisibility() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("autohide_keys", false)) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val showing = imm.isActive(terminalView)
        toggleExtraKeys(showing)
    }

    // ------------------------------------------------------------------
    // Soft keyboard.
    //
    // A single showSoftInput() is silently ignored by several ROMs right
    // after a focus change, so the call is retried with a stronger flag.
    // Actual visibility is tracked from the window insets (the only
    // reliable signal) and drives the floating toggle button.
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun showKeyboard(target: View) {
        if (android.os.SystemClock.uptimeMillis() < suppressKeyboardUntil) return
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        target.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val otherActive = TerminalBackend.splitViews.any { it !== target && imm.isActive(it) }
        if (otherActive) return
        imm.showSoftInput(target, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        target.postDelayed({
            if (imeVisible) return@postDelayed
            imm.showSoftInput(target, 0)
            target.postDelayed({
                if (!imeVisible) {
                    imm.showSoftInput(target, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                }
                updateKeyboardButton()
            }, 200)
        }, 150)
        updateKeyboardButton()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val token = currentFocus?.windowToken ?: focusedTerminalView().windowToken
        if (token != null) imm.hideSoftInputFromWindow(token, 0)
        TerminalBackend.splitViews.forEach { v -> imm.hideSoftInputFromWindow(v.windowToken, 0) }
        updateKeyboardButton()
    }

    private fun toggleKeyboard() {
        if (imeVisible) hideKeyboard() else showKeyboard(focusedTerminalView())
    }

    private fun updateKeyboardButton() {
        if (!::keyboardToggle.isInitialized) return
        keyboardToggle.alpha = if (imeVisible) 1.0f else 0.7f
        // Accent is hardcoded: the app's own theme attrs only cover
        // terminalBg/terminalText/extraKeys*, and R.attr.colorPrimary is
        // appcompat's (not resolvable through this module's R).
        val tint = if (imeVisible) {
            0xFF89B4FA.toInt()
        } else {
            tc(R.attr.terminalText, 0xFFCDD6F4.toInt())
        }
        keyboardToggle.imageTintList = android.content.res.ColorStateList.valueOf(tint)
        keyboardToggle.contentDescription =
            getString(if (imeVisible) R.string.keyboard_hide else R.string.keyboard_show)
    }

    /**
     * Safety net for taps that the terminal itself does not turn into a
     * keyboard request: a session that has not spawned an emulator yet (the
     * first seconds after opening) ignores taps completely.
     */
    private fun maybeShowKeyboardFromTap(ev: android.view.MotionEvent) {
        if (imeVisible) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - tapDownAt > 600L) return
        if (now < suppressKeyboardUntil) return
        if (kotlin.math.hypot((ev.rawX - tapDownX).toDouble(), (ev.rawY - tapDownY).toDouble()) >
            android.view.ViewConfiguration.get(this).scaledTouchSlop
        ) return
        if (panelVisible || drawerLayout.isDrawerOpen(GravityCompat.START)) return
        val target = focusedTerminalView()
        if (target.visibility != View.VISIBLE || target.height == 0) return
        if (terminalView.isSelectingText || target.isSelectingText) return

        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val lx = ev.rawX - loc[0]
        val ly = ev.rawY - loc[1]
        if (lx < 0 || ly < 0 || lx > target.width || ly > target.height) return

        // The floating toggle handles itself - do not race it.
        if (::keyboardToggle.isInitialized) {
            val bloc = IntArray(2)
            keyboardToggle.getLocationOnScreen(bloc)
            if (ev.rawX >= bloc[0] && ev.rawX <= bloc[0] + keyboardToggle.width &&
                ev.rawY >= bloc[1] && ev.rawY <= bloc[1] + keyboardToggle.height
            ) return
        }
        showKeyboard(target)
    }

    private val session: TerminalSession?
        get() = if (currentIndex in sessions.indices) sessions[currentIndex] else null

    private fun writeShellConfigs(rootfsDir: File) {
        try {
            val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
            val distro = when {
                osRelease.contains("Alpine", ignoreCase = true) -> "alpine"
                osRelease.contains("Ubuntu", ignoreCase = true) -> "ubuntu"
                osRelease.contains("Debian", ignoreCase = true) -> "debian"
                File(rootfsDir, "etc/fedora-release").exists() || osRelease.contains("Fedora", ignoreCase = true) -> "fedora"
                osRelease.contains("Void", ignoreCase = true) -> "void"
                osRelease.contains("Manjaro", ignoreCase = true) -> "manjaro"
                osRelease.contains("Arch Linux", ignoreCase = true) -> "arch"
                osRelease.contains("Artix", ignoreCase = true) -> "artix"
                osRelease.contains("Rocky Linux", ignoreCase = true) -> "rocky"
                osRelease.contains("AlmaLinux", ignoreCase = true) -> "almalinux"
                osRelease.contains("Kali", ignoreCase = true) -> "kali"
                File(rootfsDir, "etc/debian_version").exists() -> "debian"
                else -> "unknown"
            }

            val bashrc = """# ~/.bashrc
export TERM=xterm-256color
stty erase ^?
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
shopt -s histappend histreedit histverify checkwinsize cdspell dirspell
HISTSIZE=10000 HISTFILESIZE=20000
HISTCONTROL=ignoreboth:erasedups
HISTTIMEFORMAT="%F %T "
PS1='\[\e[1;32m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '
PROMPT_COMMAND='[ ${'$'}? -eq 0 ] || printf "\a"'
if [ -d /etc/bash_completion.d ]; then
    for f in /etc/bash_completion.d/*; do
        [ -f "${'$'}f" ] && . "${'$'}f"
    done
fi
alias ls='ls --color=auto'
alias ll='ls -lah'
alias la='ls -A'
alias l='ls -CF'
alias grep='grep --color=auto'
alias ..='cd ..'
alias ...='cd ../..'
alias rm='rm -i'
alias cp='cp -i'
alias mv='mv -i'
alias df='df -h'
alias du='du -h'
alias free='free -m'
alias vi='vim'
alias nano='nano -w'
"""

            val (pmUpdate, pmInstall, pmQuiet) = when (distro) {
                "alpine" -> Triple("apk update", "apk add", "-q")
                "debian", "ubuntu", "kali" -> Triple("apt-get update -qq", "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends", "-qq")
                "fedora", "rocky", "almalinux" -> Triple("dnf check-update || true", "dnf install -y", "-q")
                "void" -> Triple("xbps-install -Su", "xbps-install -S", "")
                "arch", "artix" -> Triple(":", "pacman -S --noconfirm --needed", "")
                "manjaro" -> Triple(":", "pacman -S --noconfirm --needed", "")
                else -> Triple(":", ":", "")
            }

            // Prerequisite packages for the DeepSeek Harness (dsh) toolchain.
            // Deliberately slim: python3-pip is NOT installed (~40 MB, and dsh
            // itself is a Node tool) - `apt install python3-pip` brings it
            // back in one command when a project really needs it. Everything
            // the harness runs on (git, curl, python3, node/npm) is kept.
            val tfPkgs = when (distro) {
                "alpine" -> "sudo git curl wget ca-certificates xz unzip zip python3 openssh-client procps nano"
                "debian", "ubuntu", "kali" -> "sudo git curl wget ca-certificates xz-utils unzip zip python3 openssh-client procps nano"
                "fedora", "rocky", "almalinux" -> "sudo git curl wget ca-certificates xz unzip zip python3 openssh procps nano"
                "void" -> "sudo git curl wget ca-certificates xz unzip zip python3 openssh procps nano"
                "arch", "artix", "manjaro" -> "sudo git curl wget ca-certificates xz unzip zip python openssh nano"
                else -> "sudo git curl wget ca-certificates unzip zip python3"
            }

            val rootDir = File(rootfsDir, "root")
            rootDir.mkdirs()

            // Write shell configs only when missing so user customizations
            // (e.g. a hand-written .bashrc) are never overwritten. The single
            // exception is a .bashrc from an older app version that lacks the
            // Node.js PATH entry - that line is appended non-destructively.
            val bashrcFile = File(rootDir, ".bashrc")
            if (!bashrcFile.exists()) {
                bashrcFile.writeText(bashrc)
            } else if (!bashrcFile.readText().contains("/opt/node/bin")) {
                bashrcFile.appendText("\n# tf-node-path\nexport PATH=/opt/node/bin:\$PATH\n")
            }
            val bashProfileFile = File(rootDir, ".bash_profile")
            if (!bashProfileFile.exists()) {
                bashProfileFile.writeText("""[ -f /root/.bashrc ] && . /root/.bashrc
""")
            }
            // First-time setup script (ENV): installs prerequisite packages,
            // Node.js 22 and the DeepSeek Harness (dsh) exactly once, then
            // runs a one-time space cleanup (apt metadata, docs and caches).
            // The marker string below is regenerated if it goes missing, so
            // older versions of this file are upgraded automatically.
            val startupText = """# tf-startup-v7 - generated by Terminal Free
mkdir -p /tmp
export TMPDIR=/tmp TEMP=/tmp TMP=/tmp
export PATH=/opt/node/bin:${'$'}PATH
export DSH_HOME=/workspace/.dsh
export DEBIAN_FRONTEND=noninteractive
# Keep apt small from now on: no recommends/suggests, no package
# translations, no cached .deb files and no pkgcache.bin/srcpkgcache.bin
# (109 MB in the stock image) after every apt-get update / dpkg run.
# This is metadata only - no package or tool is removed.
mkdir -p /etc/apt/apt.conf.d
cat > /etc/apt/apt.conf.d/99tf-slim <<'TFCFG'
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
APT::Keep-Downloaded-Packages "false";
APT::Update::Post-Invoke { "rm -f /var/cache/apt/pkgcache.bin /var/cache/apt/srcpkgcache.bin || true"; };
DPkg::Post-Invoke { "rm -f /var/cache/apt/pkgcache.bin /var/cache/apt/srcpkgcache.bin || true"; };
TFCFG
if [ ! -f /root/.tf_setup_done ]; then
    echo '>>> First-time setup: packages, Node.js and DeepSeek Harness (dsh)...'
    echo '>>> This runs once and may take several minutes.'
    dpkg --configure -a 2>/dev/null || true
    $pmUpdate 2>/dev/null || true
    $pmInstall $pmQuiet $tfPkgs 2>/dev/null || true
    dpkg --configure -a 2>/dev/null || true
    if ! command -v node >/dev/null 2>&1 || ! node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 22 ? 0 : 1)' 2>/dev/null; then
        echo '>>> Installing Node.js 22...'
        case "${'$'}(uname -m)" in x86_64) _narch=x64 ;; *) _narch=arm64 ;; esac
        if curl -fsSL -o /tmp/node.tar.xz "https://nodejs.org/dist/v22.23.3/node-v22.23.3-linux-${'$'}_narch.tar.xz"; then
            rm -rf /opt/node /opt/node-v22.23.3-linux-${'$'}_narch
            mkdir -p /opt
            if tar -xJf /tmp/node.tar.xz -C /opt; then
                mv "/opt/node-v22.23.3-linux-${'$'}_narch" /opt/node
                echo '>>> Node.js installed.'
            else
                echo '>>> Node.js extraction failed.'
            fi
            rm -f /tmp/node.tar.xz
        else
            echo '>>> Node.js download failed (check the network connection).'
        fi
    fi
    mkdir -p /workspace/.dsh /workspace/.tf
    if command -v npm >/dev/null 2>&1 || [ -f /opt/node/lib/node_modules/npm/bin/npm-cli.js ]; then
        echo '>>> Installing DeepSeek Harness (dsh)...'
        install_dsh() {
            if command -v npm >/dev/null 2>&1; then
                npm install -g --no-fund --no-audit @deepseek-ai/dsh && return 0
            fi
            command -v node >/dev/null 2>&1 || return 1
            node /opt/node/lib/node_modules/npm/bin/npm-cli.js install -g --no-fund --no-audit @deepseek-ai/dsh
        }
        if install_dsh; then
            dsh --version 2>/dev/null | head -1 > /workspace/.tf/dsh_version
            touch /root/.tf_setup_done
            echo ">>> dsh installed: ${'$'}(cat /workspace/.tf/dsh_version 2>/dev/null)"
            echo '>>> Open the menu -> "dsh Web" to start it, or run: dsh web --no-open'
        else
            echo '>>> dsh install FAILED - it will retry on the next terminal start.'
        fi
    else
        echo '>>> npm not available - dsh install skipped (will retry on the next terminal start).'
    fi
fi
# One-time npm upgrade: Node bundles an older npm than the latest release
# (10.9.9 vs 12.x). Needs the network - retried on every start until the
# marker appears, same policy as the dsh install above.
if [ ! -f /root/.tf_npm_updated ]; then
    if command -v npm >/dev/null 2>&1; then
        echo '>>> Updating npm to the latest version...'
        if npm install -g npm@latest --no-fund --no-audit >/dev/null 2>&1; then
            touch /root/.tf_npm_updated
            echo ">>> npm updated to ${'$'}(npm --version 2>/dev/null)"
            rm -rf /opt/node/lib/node_modules/npm/docs /opt/node/lib/node_modules/npm/man \
                /opt/node/lib/node_modules/npm/test 2>/dev/null
        else
            echo '>>> npm update failed - will retry on the next terminal start.'
        fi
    fi
fi
# One-time space cleanup (runs after the setup block so it also catches the
# npm cache left behind by the 500+ package dsh install). Removes apt
# metadata bloat, package documentation and tool caches - never a package
# or a working binary. Marker bumped to _v2 because installs stuck on
# startup-v4 never received this block at all.
if [ ! -f /root/.tf_slim_done_v2 ]; then
    echo '>>> Freeing up space (one-time cleanup)...'
    apt-get clean 2>/dev/null
    rm -rf /var/cache/apt/archives/*.deb /var/cache/apt/*.bin 2>/dev/null
    rm -rf /var/lib/apt/lists/* 2>/dev/null
    mkdir -p /var/lib/apt/lists/partial /var/cache/apt/archives/partial
    touch /var/lib/apt/lists/lock /var/cache/apt/archives/lock 2>/dev/null
    rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/* 2>/dev/null
    rm -rf /usr/share/lintian/* /usr/share/bug/* /usr/share/lintian 2>/dev/null
    rm -rf /var/log/* /var/tmp/* /tmp/* 2>/dev/null
    rm -rf /root/.npm /root/.cache 2>/dev/null
    if command -v python3 >/dev/null 2>&1; then
        python3 -m pip cache purge >/dev/null 2>&1
    fi
    # Trimmed Node: drop headers, docs, tests and corepack (npm stays whole;
    # if a native module ever needs headers, node-gyp downloads them).
    rm -rf /opt/node/share /opt/node/include \
        /opt/node/lib/node_modules/corepack \
        /opt/node/lib/node_modules/npm/docs /opt/node/lib/node_modules/npm/man \
        /opt/node/lib/node_modules/npm/test 2>/dev/null
    rm -f /opt/node/bin/corepack* 2>/dev/null
    touch /root/.tf_slim_done_v2
    echo '>>> Cleanup done. (If apt ever says "Unable to locate", run: apt-get update)'
fi
if command -v bash >/dev/null 2>&1; then
    exec bash -i
fi
"""
            val startupFile = File(rootDir, ".startup")
            if (!startupFile.exists() || !startupFile.readText().contains("# tf-startup-v7")) {
                startupFile.writeText(startupText)
            }
        } catch (e: Exception) {
            android.util.Log.w("TerminalActivity", "writeShellConfigs failed: ${e.message}")
        }
    }

    private fun createNewSession() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val scrollback = intArrayOf(500, 1000, 2000, 3000, 5000, 7500, 10000, 15000, 20000, 30000)[prefs.getInt("scrollback", 4).coerceIn(0, 9)]
        val startInner = pendingStartDir.also { pendingStartDir = null }
        val rootfsDir = DistroInstaller(applicationContext).getRootfsDir(distroName)
        if (!rootfsDir.exists()) {
            showError("Distro $distroName not installed.\nRun installer first.")
            return
        }

        val repairLog = DistroInstaller(applicationContext).repairRootfs(rootfsDir)
        if (repairLog.contains("WARN") || repairLog.contains("missing")) {
            android.util.Log.w("TerminalActivity", "Rootfs issues:\n$repairLog")
        }

        // Ensure /tmp and executable binaries in rootfs (proot needs both)
        File(rootfsDir, "tmp").mkdirs()
        val busybox = File(rootfsDir, "bin/busybox")
        if (busybox.exists() && !busybox.canExecute()) {
            busybox.setExecutable(true, true)
        }
        // Also set bin/sh etc.
        for (name in listOf("sh", "ash", "bash")) {
            val f = File(rootfsDir, "bin/$name")
            if (f.exists() && !f.canExecute()) {
                f.setExecutable(true, true)
            }
        }

        val backend = terminalBackend ?: TerminalBackend(terminalView, this).also {
            terminalBackend = it
            terminalView.setTerminalViewClient(it)
            wireBackend(it)
        }

        // ---- Distro init & proot launch ----
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val prootBin = "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libproot-loader.so"
        val prootLoader32 = "$nativeLibDir/libproot-loader32.so"
        val ldr32 = if (File(prootLoader32).exists()) "export PROOT_LOADER_32=$prootLoader32\n" else ""
        val rp = rootfsDir.absolutePath

        writeShellConfigs(rootfsDir)

        // Host-backed workspace: visible inside the container as /workspace.
        // This is the ONLY bridge to device files (copied in/out by the app's
        // file browser); no device filesystem path is ever bound.
        val wsDir = File(filesDir, "workspace").apply { mkdirs() }
        File(rootfsDir, "workspace").mkdirs()

        val startHost = startInner?.let { File(rp, it.removePrefix("/")) }?.absolutePath
            ?: filesDir.absolutePath
        val launchSh = File(filesDir, "launch.sh")
        launchSh.parentFile?.mkdirs()
        launchSh.writeText("""#!/system/bin/sh
export HOME=/root
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin
export ENV=/root/.startup
export TMPDIR=/tmp
export TMP=/tmp
export TEMP=/tmp
export TERM=xterm-256color
export COLORTERM=truecolor
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DSH_HOME=/workspace/.dsh
export PROOT_LOADER=$prootLoader
${ldr32}export PROOT_TMP_DIR=$rp/tmp
mkdir -p "$rp/tmp"
WS="$wsDir"
mkdir -p "${'$'}WS"
exec $prootBin -0 -L -r "$rp" -w ${startInner ?: "/workspace"} --link2symlink --sysvipc --kill-on-exit \
    -b /dev -b /proc -b /sys -b /system -b /apex -b /linkerconfig/ld.config.txt \
    -b "${'$'}WS:/workspace" \
    /system/bin/sh -i 2>&1
""")
        launchSh.setExecutable(true, true)

        val args = arrayOf("-c", launchSh.absolutePath)

        val s = TerminalSession(
            "/system/bin/sh", startHost,
            args, emptyArray(),
            scrollback,
            backend
        )
        s.mSessionName = distroName

        wireBackend(backend)

        sessionModel.addSession(s)
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        currentFontSize = prefs.getInt("font_size", 20)
        terminalView.setTextSize(currentFontSize)
        applyFontFromPrefs(prefs)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))

        terminalView.post {
            terminalView.requestFocus()
            terminalView.isFocusableInTouchMode = true
        }

        updateDrawer()
    }

    private fun switchToSession(index: Int) {
        if (index !in sessions.indices || index == currentIndex) return
        sessionModel.switchToSession(index)
        terminalView.attachSession(sessions[index])
        terminalView.onScreenUpdated()
        supportActionBar?.title = sessions[index].mSessionName.ifEmpty {
            distroName.replaceFirstChar { it.uppercase() }
        }
        updateDrawer()
    }

    private fun handleSessionFinished(finishedSession: TerminalSession) {
        val idx = sessions.indexOf(finishedSession)
        if (idx < 0) return
        sessionModel.removeSession(idx)
        if (splitActive) {
            exitSplit()
        }
        if (sessions.isEmpty()) {
            finish()
        } else {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
            updateDrawer()
        }
    }

    private var splitActive = false
    private var splitBackend: TerminalBackend? = null
    private var splitLeftBackend: TerminalBackend? = null
    private val splitViewSession = mutableMapOf<com.termux.view.TerminalView, TerminalSession>()

    private fun toggleSplit() {
        if (splitActive) {
            exitSplit()
            return
        }
        if (sessions.size < 2) {
            Toast.makeText(this, "Open a second session to use split view", Toast.LENGTH_SHORT).show()
            return
        }
        splitActive = true
        val container = findViewById<LinearLayout>(R.id.split_container)
        val left = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_left)
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        val secondaryIdx = (currentIndex + 1) % sessions.size

        terminalView.visibility = View.GONE
        findViewById<View>(R.id.search_highlight_overlay).visibility = View.GONE
        container.visibility = View.VISIBLE

        val lb = TerminalBackend(left, this).also {
            splitLeftBackend = it
            wireBackend(it)
            it.onTap = { splitSelect(left) }
        }
        val rb = TerminalBackend(right, this).also {
            splitBackend = it
            wireBackend(it)
            it.onTap = { splitSelect(right) }
        }
        sessions[currentIndex].updateTerminalSessionClient(lb)
        sessions[secondaryIdx].updateTerminalSessionClient(rb)
        left.setTerminalViewClient(lb)
        right.setTerminalViewClient(rb)
        TerminalBackend.splitViews.clear()
        TerminalBackend.splitViews.add(left)
        TerminalBackend.splitViews.add(right)
        splitViewSession[left] = sessions[currentIndex]
        splitViewSession[right] = sessions[secondaryIdx]

        val bg = tc(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        for (view in listOf(left, right)) {
            view.attachSession(splitViewSession[view])
            view.onScreenUpdated()
            view.setTextSize(currentFontSize)
            view.setBackgroundColor(bg)
            applyFontToView(view, getSharedPreferences("settings", MODE_PRIVATE))
        }
        left.requestFocus()
        updateSplitButton()
    }

    private fun exitSplit() {
        if (!splitActive) return
        splitActive = false
        val container = findViewById<LinearLayout>(R.id.split_container)
        splitViewSession.clear()
        TerminalBackend.splitViews.clear()
        splitBackend = null
        splitLeftBackend = null
        for (s in sessions) {
            terminalBackend?.let { s.updateTerminalSessionClient(it) }
        }
        container.visibility = View.GONE
        terminalView.visibility = View.VISIBLE
        findViewById<View>(R.id.search_highlight_overlay).visibility = View.VISIBLE
        if (sessions.isNotEmpty()) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        terminalView.requestFocus()
        updateSplitButton()
    }

    private fun splitSelect(view: com.termux.view.TerminalView) {
        val s = splitViewSession[view] ?: return
        val idx = sessions.indexOf(s)
        if (idx < 0 || idx == currentIndex) return
        sessionModel.switchToSession(idx)
        supportActionBar?.title = s.mSessionName.ifEmpty {
            distroName.replaceFirstChar { it.uppercase() }
        }
        updateDrawer()
    }

    private fun updateSplitButton() {
        setCardButtonBg(findViewById<TextView>(R.id.panel_split), splitActive)
    }

    private fun focusedSplitView(): com.termux.view.TerminalView? {
        if (!splitActive) return null
        val left = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_left)
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        return if (right.hasFocus()) right else left
    }

    private fun focusedBackend(): TerminalBackend? {
        if (!splitActive) return terminalBackend
        val right = findViewById<com.termux.view.TerminalView>(R.id.terminal_view_right)
        return if (right.hasFocus()) splitBackend else splitLeftBackend
    }

    private fun closeSession(index: Int) {
        if (sessions.size <= 1) {
            // Never kill the only session from a stray tap - tell the user why.
            Toast.makeText(this, getString(R.string.session_last_one), Toast.LENGTH_SHORT).show()
            return
        }
        sessionModel.removeSession(index)
        if (currentIndex >= 0) {
            terminalView.attachSession(sessions[currentIndex])
            terminalView.onScreenUpdated()
        }
        updateDrawer()
    }

    private fun updateDrawer() {
        findViewById<TextView>(R.id.session_count).text = getString(R.string.session_count_format, sessions.size)
        sessionListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            sessionListContainer.addView(TextView(this).apply {
                text = getString(R.string.no_sessions)
                setTextColor(0xFF6C7086.toInt())
                textSize = 13f
                setPadding(16, 20, 16, 20)
            })
            return
        }
        for (i in sessions.indices) {
            val bgColor = if (i == currentIndex)
                tc(R.attr.extraKeysBg, 0xFF181825.toInt())
            else 0
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(4, 4, 4, 4) }
                setCardBackgroundColor(bgColor)
                radius = 10f
                cardElevation = 0f
                setOnClickListener { switchToSession(i); drawerLayout.closeDrawers() }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    // A session row is a primary touch target: the old
                    // 10dp padding plus a 12dp close icon made switching and
                    // closing sessions nearly impossible to hit.
                    setPadding(dp(14), dp(12), dp(6), dp(12))
                    addView(TextView(context).apply {
                        text = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                        setTextColor(tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
                        textSize = 15f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply { setMargins(0, 0, dp(8), 0) }
                        setOnLongClickListener {
                            val currentLabel = sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                            val input = android.widget.EditText(this@TerminalActivity).apply { setText(currentLabel) }
                            androidx.appcompat.app.AlertDialog.Builder(this@TerminalActivity)
                                .setTitle("Rename session")
                                .setView(input)
                                .setPositiveButton("Rename") { _, _ ->
                                    val newName = input.text.toString().trim()
                                    if (newName.isNotEmpty()) {
                                        sessions[i].mSessionName = newName
                                        updateDrawer()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        }
                    })
                    val dotSize = dp(10)
                    addView(android.view.View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            setMargins(0, 0, dp(10), 0)
                        }
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            if (i == currentIndex) {
                                setColor(0xFFA6E3A1.toInt())
                            } else {
                                setColor(0x00000000)
                                setStroke(dp(2), 0xFF6C7086.toInt())
                            }
                        }
                    })
                    addView(ImageView(context).apply {
                        // 44dp touch target with a ripple around an 18dp glyph.
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                            .apply { gravity = Gravity.CENTER_VERTICAL }
                        val ripple = obtainStyledAttributes(
                            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                        )
                        background = ripple.getDrawable(0)
                        ripple.recycle()
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(13), dp(13), dp(13), dp(13))
                        setImageDrawable(
                            androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                context, R.drawable.ic_close
                            )
                        )
                        imageTintList = android.content.res.ColorStateList.valueOf(
                            if (i == currentIndex) 0xFFA6E3A1.toInt() else 0xFF8A8F9E.toInt()
                        )
                        contentDescription = getString(
                            R.string.session_close_desc,
                            sessions[i].mSessionName.ifEmpty { "session ${i + 1}" }
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { closeSession(i) }
                    })
                })
            }
            sessionListContainer.addView(card)
        }
    }

    private fun exportCurrentOutput() {
        val s = session ?: return
        drawerLayout.closeDrawers()
        Thread {
            try {
                val text = s.emulator.getScreen().getTranscriptText()
                var dir = File(
                    android.os.Environment.getExternalStorageDirectory(), "TerminalFree/exports"
                )
                dir.mkdirs()
                if (!dir.exists()) dir = File(filesDir, "exports").apply { mkdirs() }
                val f = File(dir, "${distroName}-${System.currentTimeMillis()}.txt")
                f.writeText(text)
                runOnUiThread {
                    Toast.makeText(this, "Exported: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun copySelectedText() {
        if (terminalView.isSelectingText) {
            val text = terminalView.getSelectedText()
            if (!text.isNullOrEmpty()) {
                val clip = getSystemService(android.content.ClipboardManager::class.java)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
                terminalView.stopTextSelectionMode()
                Toast.makeText(this, "Copied ${text.length} chars", Toast.LENGTH_SHORT).show()
                return
            }
        }
        session?.let {
            val text = it.emulator.getScreen().getTranscriptText()
            val clip = getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("terminal", text))
            Toast.makeText(this, "Copied entire output (${text.length} chars)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pasteClipboard() {
        val clip = getSystemService(android.content.ClipboardManager::class.java)
        val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        if (text.length > 500) {
            Thread {
                val chunkSize = 4096
                var offset = 0
                while (offset < text.length) {
                    val end = (offset + chunkSize).coerceAtMost(text.length)
                    val chunk = text.substring(offset, end)
                    session?.write(chunk)
                    offset = end
                    Thread.sleep(10)
                }
            }.start()
        } else {
            session?.write(text)
        }
    }

    private fun showError(msg: String) {
        val errorFile = File(cacheDir, "opencode_error.txt")
        errorFile.writeText(msg)

        terminalView.setTextSize(14)
        terminalView.setBackgroundColor(tc(R.attr.terminalBg, 0xFF1E1E2E.toInt()))
        val backend = TerminalBackend(terminalView, this)
        terminalView.setTerminalViewClient(backend)
        val s = TerminalSession(
            "/system/bin/toybox", filesDir.absolutePath,
            arrayOf("cat", errorFile.absolutePath), emptyArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, backend
        )
        s.mSessionName = "Error"
        terminalView.attachSession(s)
        terminalView.onScreenUpdated()
        terminalView.post { terminalView.requestFocus() }
    }

    private fun startForegroundService() {
        TerminalService.ensure(this)
    }

    override fun onResume() {
        super.onResume()
        terminalView.requestFocus()
        terminalView.onScreenUpdated()
        updateKeyboardButton()
        titleHandler.postDelayed(titleRunnable, 1000)
    }

    override fun onPause() {
        titleHandler.removeCallbacks(titleRunnable)
        super.onPause()
    }

    private fun updateCwdTitle() {
        val s = session ?: return
        if (!s.isRunning) return
        val cwd = s.cwd ?: return
        val rootfs = DistroInstaller(applicationContext).getRootfsDir(distroName).absolutePath
        val wsHost = File(filesDir, "workspace").absolutePath
        val inner = when {
            cwd.startsWith(wsHost) -> "/workspace" + cwd.removePrefix(wsHost)
            cwd.startsWith(rootfs) -> cwd.removePrefix(rootfs).ifEmpty { "/" }
            else -> cwd
        }
        val shortPath = if (inner.count { it == '/' } <= 2) inner
            else "/${inner.split("/").filter { it.isNotEmpty() }.takeLast(2).joinToString("/")}"
        val name = s.mSessionName.ifEmpty { distroName }
        val title = "$name \u203A $shortPath"
        if (supportActionBar?.title != title) {
            supportActionBar?.title = title
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (sessions.isNotEmpty()) {
            val newDistro = intent.getStringExtra(EXTRA_DISTRO)
            val target = if (newDistro != null)
                sessions.indexOfFirst { it.mSessionName.equals(newDistro, ignoreCase = true) }
            else -1
            if (target >= 0) {
                sessionModel.switchToSession(target)
                terminalView.attachSession(sessions[target])
                supportActionBar?.title = sessions[target].mSessionName.ifEmpty {
                    newDistro!!.replaceFirstChar { it.uppercase() }
                }
            } else {
                terminalView.attachSession(sessions[currentIndex])
            }
            terminalView.onScreenUpdated()
            terminalView.requestFocus()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(nightReceiver)
        if (sessions.isEmpty()) {
            TerminalService.stop(this)
        } else {
            TerminalService.ensure(this)
        }
        terminalBackend?.onSessionFinished = null
        terminalBackend = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tapDownX = ev.rawX
                tapDownY = ev.rawY
                tapDownAt = android.os.SystemClock.uptimeMillis()
                if (ev.y < 100 && ev.rawY < 400) {
                    val prefs = getSharedPreferences("settings", MODE_PRIVATE)
                    if (prefs.getBoolean("autohide_keys", false)) {
                        updateExtraKeysVisibility()
                    }
                    toggleQuickPanel()
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                // Dispatch first: a tap on a URL inside the terminal opens a
                // dialog and pushes suppressKeyboardUntil, which is then
                // respected by maybeShowKeyboardFromTap().
                val handled = super.dispatchTouchEvent(ev)
                maybeShowKeyboardFromTap(ev)
                return handled
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // While the IME is up, Back must dismiss it - TerminalView would
        // otherwise turn it into ESC and the keyboard could never be closed
        // from the screen.
        if (event.keyCode == KeyEvent.KEYCODE_BACK && imeVisible) {
            if (event.action == KeyEvent.ACTION_DOWN) hideKeyboard()
            return true
        }
        if (currentIndex !in sessions.indices) return super.dispatchKeyEvent(event)
        if ((event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) &&
            isSearchPanelVisible()) {
            return findViewById<android.widget.EditText>(R.id.search_input).dispatchKeyEvent(event)
        }
        @Suppress("DEPRECATION")
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_MULTIPLE -> {
                if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                    @Suppress("DEPRECATION") session?.write(event.characters ?: ""); true
                } else super.dispatchKeyEvent(event)
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add(0, 1, 0, "Sessions")
        menu?.add(0, 2, 0, "New Session")
        menu?.add(0, 3, 0, "Font +")
        menu?.add(0, 4, 0, "Font -")
        menu?.add(0, 5, 0, "Reset")
        val fontSub = menu?.addSubMenu(0, 7, 0, "Fonts")
        fontSub?.add(0, 71, 0, "JetBrains Mono")
        fontSub?.add(0, 72, 0, "Fira Code")
        fontSub?.add(0, 73, 0, "Source Code Pro")
        fontSub?.add(0, 74, 0, "Ubuntu Mono")
        fontSub?.add(0, 75, 0, "monospace")
        fontSub?.add(0, 76, 0, "Droid Sans Mono")
        fontSub?.add(0, 77, 0, "Noto Sans Mono")
        fontSub?.add(0, 78, 0, "Cascadia Code")
        rebuildCustomFontMenuItems(menu)

        val themeSub = menu?.addSubMenu(0, 6, 0, "Theme")
        themeSub?.add(0, 61, 0, "Catppuccin Dark")
        themeSub?.add(0, 62, 0, "Green Terminal")
        themeSub?.add(0, 63, 0, "Light")
        themeSub?.add(0, 69, 0, "Red Terminal")
        themeSub?.add(0, 68, 0, "AMOLED Black")
        themeSub?.add(0, 64, 0, "Dracula")
        themeSub?.add(0, 65, 0, "Nord")
        themeSub?.add(0, 66, 0, "Tokyo Night")
        themeSub?.add(0, 67, 0, "Gruvbox Dark")
        themeSub?.add(0, 70, 0, "Custom")
        themeSub?.add(0, 79, 0, "Dynamic")
        menu?.add(0, 8, 0, "Find")
        menu?.add(0, 9, 0, "Snippets")
        menu?.add(0, 10, 0, "Quick settings")
        menu?.add(0, 11, 0, "Split view")
        menu?.add(0, 14, 0, "dsh Web")
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        rebuildCustomFontMenuItems(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun rebuildCustomFontMenuItems(menu: Menu?) {
        val fontSub = menu?.findItem(7)?.subMenu ?: return
        for (i in 0 until 10) {
            fontSub.removeItem(100 + i)
        }
        customFontFiles().forEachIndexed { i, f ->
            fontSub.add(0, 100 + i, 0, "${f.name.removeSuffix(".ttf").removeSuffix(".TTF").removeSuffix(".otf").removeSuffix(".OTF")} (custom)")
        }
    }

    private fun applyTerminalTheme(themeName: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        prefs.edit { putString("theme", themeName) }
        NightModeReceiver.notifyChanged(this, prefs)
        updateTerminalBg()
        val themeRes = when (themeName) {
            "red" -> R.style.Theme_RedTermApp_Red
            "amoled" -> R.style.Theme_RedTermApp_AMOLED
            "green" -> R.style.Theme_RedTermApp_Green
            "light" -> R.style.Theme_RedTermApp_Light
            "dracula" -> R.style.Theme_RedTermApp_Dracula
            "nord" -> R.style.Theme_RedTermApp_Nord
            "tokyo" -> R.style.Theme_RedTermApp_Tokyo
            "gruvbox" -> R.style.Theme_RedTermApp_Gruvbox
            "dynamic" -> R.style.Theme_RedTermApp
            else -> R.style.Theme_RedTermApp
        }
        val wrapped = ContextThemeWrapper(this, themeRes)
        fun tca(attr: Int, default: Int): Int {
            val ta = wrapped.obtainStyledAttributes(intArrayOf(attr))
            val c = ta.getColor(0, default); ta.recycle(); return c
        }
        val dynamic = if (themeName == "dynamic") dynamicTerminalColors() else null
        val bg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
            dynamic != null -> dynamic.first
            else -> tca(R.attr.terminalBg, 0xFF1E1E2E.toInt())
        }
        val extraBg = when {
            themeName == "custom" -> prefs.getInt("custom_bg", 0xFF0A0A0A.toInt())
            dynamic != null -> dynamic.second
            else -> tca(R.attr.extraKeysBg, 0xFF181825.toInt())
        }
        val textColor = when {
            themeName == "custom" -> prefs.getInt("custom_text", 0xFFCDD6F4.toInt())
            dynamic != null -> dynamic.third
            else -> tca(R.attr.terminalText, 0xFFCDD6F4.toInt())
        }

        val opacity = prefs.getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        val bgWithAlpha = (bg and 0x00FFFFFF) or (alpha shl 24)
        val extraBgWithAlpha = (extraBg and 0x00FFFFFF) or (alpha shl 24)
        terminalView.setBackgroundColor(bgWithAlpha)
        drawerLayout.setBackgroundColor(bg)
        val row1 = findViewById<LinearLayout>(R.id.extra_keys_container).apply { setBackgroundColor(extraBgWithAlpha) }
        val row2 = findViewById<LinearLayout>(R.id.extra_keys_container_row2).apply { setBackgroundColor(extraBgWithAlpha) }
        for (i in 0 until row1.childCount) (row1.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
        for (i in 0 until row2.childCount) (row2.getChildAt(i) as? android.widget.TextView)?.setTextColor(textColor)
    }

    private fun updateTerminalBg() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val theme = prefs.getString("theme", "amoled")
        val bg = when {
            theme == "custom" -> {
                prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
            }
            theme == "dynamic" -> {
                dynamicTerminalColors()?.first ?: 0xFF1E1E2E.toInt()
            }
            else -> {
                val themeRes = when (theme) {
                    "red" -> R.style.Theme_RedTermApp_Red
                    "amoled" -> R.style.Theme_RedTermApp_AMOLED
                    "green" -> R.style.Theme_RedTermApp_Green
                    "light" -> R.style.Theme_RedTermApp_Light
                    "dracula" -> R.style.Theme_RedTermApp_Dracula
                    "nord" -> R.style.Theme_RedTermApp_Nord
                    "tokyo" -> R.style.Theme_RedTermApp_Tokyo
                    "gruvbox" -> R.style.Theme_RedTermApp_Gruvbox
                    else -> R.style.Theme_RedTermApp
                }
                val wrapped = ContextThemeWrapper(this, themeRes)
                val ta = wrapped.obtainStyledAttributes(intArrayOf(R.attr.terminalBg))
                val c = ta.getColor(0, 0xFF1E1E2E.toInt())
                ta.recycle()
                c
            }
        }
        val opacity = prefs.getInt("terminal_opacity", 10).coerceIn(0, 10)
        val alpha = (opacity * 25.5).toInt().coerceIn(0, 255)
        val bgWithAlpha = (bg and 0x00FFFFFF) or (alpha shl 24)
        terminalView.setBackgroundColor(bgWithAlpha)
    }

    private fun dynamicTerminalColors(): Triple<Int, Int, Int>? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return try {
            Triple(
                getColor(android.R.color.system_neutral1_1000),
                getColor(android.R.color.system_neutral1_900),
                getColor(android.R.color.system_neutral1_100)
            )
        } catch (_: Exception) {
            null
        }
    }

    private var panelVisible = false

    private fun setupSearchPanel() {
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        val prev = findViewById<android.widget.TextView>(R.id.search_prev)
        val next = findViewById<android.widget.TextView>(R.id.search_next)
        val close = findViewById<android.widget.TextView>(R.id.search_close)

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch(input.text.toString())
                true
            } else false
        }

        prev.setOnClickListener { navigateSearch(-1) }
        next.setOnClickListener { navigateSearch(1) }
        close.setOnClickListener { closeSearchPanel() }
    }

    private fun isSearchPanelVisible(): Boolean =
        findViewById<android.widget.LinearLayout>(R.id.search_panel).isVisible

    private fun searchInputKey(keyCode: Int) {
        findViewById<android.widget.EditText>(R.id.search_input)
            .dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }

    private fun closeSearchPanel() {
        findViewById<android.widget.LinearLayout>(R.id.search_panel).visibility = android.view.View.GONE
        searchHighlight.clear()
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        input.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        terminalView.requestFocus()
    }

    private fun setupQuickPanel(prefs: android.content.SharedPreferences) {
        val panel = findViewById<LinearLayout>(R.id.quick_panel)
        findViewById<TextView>(R.id.panel_close).setOnClickListener { toggleQuickPanel() }

        findViewById<TextView>(R.id.panel_wakelock).apply {
            setOnClickListener {
                if (prefs.getBoolean("wakelock", false)) {
                    // The screen now stays on globally while the app is in
                    // the foreground (RedTermApp re-applies FLAG_KEEP_SCREEN_ON
                    // on every resume), so this toggle no longer clears the
                    // flag - it only tracks the preference.
                    prefs.edit { putBoolean("wakelock", false) }
                    setCardButtonBg(this, false)
                } else {
                    prefs.edit { putBoolean("wakelock", true) }
                    setCardButtonBg(this, true)
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                TerminalService.ensure(this@TerminalActivity)
            }
            setCardButtonBg(this, prefs.getBoolean("wakelock", false))
        }
        if (prefs.getBoolean("wakelock", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        findViewById<TextView>(R.id.panel_split).setOnClickListener { toggleSplit() }
        updateSplitButton()
        findViewById<TextView>(R.id.panel_font_up).setOnClickListener {
            currentFontSize = (currentFontSize + 2).coerceAtMost(36)
            terminalView.setTextSize(currentFontSize)
            prefs.edit { putInt("font_size", currentFontSize) }
        }
        findViewById<TextView>(R.id.panel_font_down).setOnClickListener {
            currentFontSize = (currentFontSize - 2).coerceAtLeast(8)
            terminalView.setTextSize(currentFontSize)
            prefs.edit { putInt("font_size", currentFontSize) }
        }
        findViewById<TextView>(R.id.panel_reset).setOnClickListener {
            session?.reset()
            prefs.edit { putString("font", "monospace") }
            applyFontFromPrefs(prefs)
            currentFontSize = 20
            prefs.edit { putInt("font_size", 20) }
            terminalView.setTextSize(20)
            applyTerminalTheme("amoled")
            toggleQuickPanel()
        }
    }

    private fun toggleQuickPanel() {
        panelVisible = !panelVisible
        findViewById<LinearLayout>(R.id.quick_panel).visibility = if (panelVisible) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setCardButtonBg(tv: TextView, active: Boolean) {
        tv.setBackgroundColor(if (active) 0xFF45475A.toInt() else 0x33000000)
        tv.setTextColor(if (active) 0xFF89B4FA.toInt() else tc(R.attr.terminalText, 0xFFCDD6F4.toInt()))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            1 -> { drawerLayout.openDrawer(GravityCompat.START); true }
            2 -> { createNewSession(); true }
            3 -> { currentFontSize = (currentFontSize + 2).coerceAtMost(36); terminalView.setTextSize(currentFontSize); true }
            4 -> { currentFontSize = (currentFontSize - 2).coerceAtLeast(8); terminalView.setTextSize(currentFontSize); true }
             5 -> {
                session?.reset()
                prefs.edit { putString("font", "monospace") }
                applyFontFromPrefs(prefs)
                currentFontSize = 20
                prefs.edit { putInt("font_size", 20) }
                terminalView.setTextSize(20)
                applyTerminalTheme("amoled")
                true
            }
              61 -> { applyTerminalTheme("default"); true }
              62 -> { applyTerminalTheme("green"); true }
              63 -> { applyTerminalTheme("light"); true }
              69 -> { applyTerminalTheme("amoled"); true }
              68 -> { applyTerminalTheme("amoled"); true }
              64 -> { applyTerminalTheme("dracula"); true }
              65 -> { applyTerminalTheme("nord"); true }
              66 -> { applyTerminalTheme("tokyo"); true }
              67 -> { applyTerminalTheme("gruvbox"); true }
               70 -> { applyTerminalTheme("custom"); true }
               79 -> { applyTerminalTheme("dynamic"); true }
               71 -> { prefs.edit { putString("font", "JetBrains Mono") }; applyFontFromPrefs(prefs); true }
              72 -> { prefs.edit { putString("font", "Fira Code") }; applyFontFromPrefs(prefs); true }
              73 -> { prefs.edit { putString("font", "Source Code Pro") }; applyFontFromPrefs(prefs); true }
              74 -> { prefs.edit { putString("font", "Ubuntu Mono") }; applyFontFromPrefs(prefs); true }
              75 -> { prefs.edit { putString("font", "monospace") }; applyFontFromPrefs(prefs); true }
              76 -> { prefs.edit { putString("font", "Droid Sans Mono") }; applyFontFromPrefs(prefs); true }
              77 -> { prefs.edit { putString("font", "Noto Sans Mono") }; applyFontFromPrefs(prefs); true }
               78 -> { prefs.edit { putString("font", "Cascadia Code") }; applyFontFromPrefs(prefs); true }
               100 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(0)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               101 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(1)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               102 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(2)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               103 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(3)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               104 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(4)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               105 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(5)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               106 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(6)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               107 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(7)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               108 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(8)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
               109 -> { prefs.edit { putString("font", "custom:${customFontFiles().getOrNull(9)?.name ?: ""}") }; applyFontFromPrefs(prefs); true }
                8 -> { toggleSearch(); true }
                9 -> { showSnippetsDialog(); true }
                10 -> { toggleQuickPanel(); true }
                11 -> { toggleSplit(); true }
                14 -> { openDshWebUi(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Starts the dsh Web server inside the container and opens it in the built-in browser. */
    private fun openDshWebUi() {
        Toast.makeText(this, getString(R.string.dsh_open_web_starting), Toast.LENGTH_SHORT).show()
        DshManager.ensureWebServer(this) { ok, msg ->
            if (ok) {
                BrowserActivity.launch(this, DshManager.WEB_URL)
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.dsh_open_web_failed)
                    .setMessage(msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private val fontCache = HashMap<String, android.graphics.Typeface?>()

    private fun customFontFiles(): List<File> =
        File(filesDir, "fonts").listFiles { f ->
            f.isFile && (f.extension.equals("ttf", true) || f.extension.equals("otf", true))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun loadFont(assetPath: String): android.graphics.Typeface? =
        fontCache.getOrPut(assetPath) {
            try {
                android.graphics.Typeface.createFromAsset(assets, assetPath)
            } catch (_: Exception) {
                null
            }
        }

    private fun applyFontFromPrefs(prefs: android.content.SharedPreferences) {
        val tf = fontFromPrefs(prefs)
        terminalView.setTypeface(tf)
    }

    private fun fontFromPrefs(prefs: android.content.SharedPreferences): android.graphics.Typeface {
        val fontName = prefs.getString("font", "monospace")
        val tf = when {
            fontName != null && fontName.startsWith("custom:") ->
                try {
                    android.graphics.Typeface.createFromFile(
                        File(filesDir, "fonts/${fontName.removePrefix("custom:")}")
                    )
                } catch (_: Exception) {
                    null
                }
            else -> when (fontName) {
                "JetBrains Mono" -> loadFont("fonts/JetBrainsMono.ttf")
                "Fira Code" -> loadFont("fonts/FiraCode.ttf")
                "Source Code Pro" -> loadFont("fonts/SourceCodePro.ttf")
                "Ubuntu Mono" -> loadFont("fonts/UbuntuMono.ttf")
                "Droid Sans Mono" -> loadFont("fonts/DroidSansMono.ttf")
                "Noto Sans Mono" -> loadFont("fonts/NotoSansMono.ttf")
                "Cascadia Code" -> loadFont("fonts/CascadiaCode.ttf")
                else -> android.graphics.Typeface.MONOSPACE
            }
        }
        return tf ?: android.graphics.Typeface.MONOSPACE
    }

    private fun applyFontToView(view: com.termux.view.TerminalView, prefs: android.content.SharedPreferences) {
        view.setTypeface(fontFromPrefs(prefs))
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        val theme = NightModeReceiver.effectiveTheme(prefs)
        if (theme == "dynamic") {
            setTheme(R.style.Theme_RedTermApp)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try {
                    com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
                } catch (_: Exception) {}
            }
            return
        }
        when (theme) {
            "red" -> setTheme(R.style.Theme_RedTermApp_Red)
            "amoled" -> setTheme(R.style.Theme_RedTermApp_AMOLED)
            "green" -> setTheme(R.style.Theme_RedTermApp_Green)
            "light" -> setTheme(R.style.Theme_RedTermApp_Light)
            "dracula" -> setTheme(R.style.Theme_RedTermApp_Dracula)
            "nord" -> setTheme(R.style.Theme_RedTermApp_Nord)
            "tokyo" -> setTheme(R.style.Theme_RedTermApp_Tokyo)
            "gruvbox" -> setTheme(R.style.Theme_RedTermApp_Gruvbox)
            "custom" -> {
                setTheme(R.style.Theme_RedTermApp_Custom)
                val bg = prefs.getInt("custom_bg", 0xFF1E1E2E.toInt())
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(bg),
                    navigationBarStyle = SystemBarStyle.dark(bg)
                )
            }
            else -> setTheme(R.style.Theme_RedTermApp)
        }
    }

    private fun toggleSearch() {
        val panel = findViewById<android.widget.LinearLayout>(R.id.search_panel)
        if (panel.isVisible) {
            closeSearchPanel()
            return
        }
        panel.visibility = android.view.View.VISIBLE
        val input = findViewById<android.widget.EditText>(R.id.search_input)
        input.requestFocus()
        input.setText("")
        searchMatches.clear()
        searchIndex = -1
        findViewById<android.widget.TextView>(R.id.search_count).text = "0/0"
    }

    private var searchRunId = 0

    private fun performSearch(query: String) {
        searchMatches.clear()
        searchIndex = -1

        val countView = findViewById<android.widget.TextView>(R.id.search_count)

        if (query.isEmpty()) {
            countView.text = "0/0"
            searchHighlight.clear()
            return
        }

        val emulator = terminalView.mEmulator ?: return
        val runId = ++searchRunId

        Thread {
            val matches = try {
                scanTranscript(query, emulator)
            } catch (t: Throwable) {
                emptyList<SearchMatch>()
            }
            runOnUiThread {
                if (runId != searchRunId) return@runOnUiThread
                if (matches.isEmpty()) {
                    countView.text = "0/0"
                    searchHighlight.clear()
                } else {
                    searchMatches.addAll(matches)
                    searchIndex = 0
                    countView.text = getString(R.string.search_match_count_format, 1, searchMatches.size)
                    scrollToMatch(searchMatches[0])
                }
            }
        }.start()
    }

    private fun scanTranscript(query: String, emulator: TerminalEmulator): List<SearchMatch> {
        val buffer = emulator.getScreen()
        val lastRow = emulator.mRows - 1
        val maxCol = emulator.mColumns - 1
        val results = ArrayList<SearchMatch>()
        for (row in -buffer.getActiveTranscriptRows()..lastRow) {
            val internal = try {
                buffer.externalToInternalRow(row)
            } catch (e: IllegalArgumentException) {
                continue
            }
            val terminalRow = buffer.allocateFullLineIfNecessary(internal)
            val line = String(terminalRow.mText, 0, terminalRow.getSpaceUsed())
            var col = line.indexOf(query, ignoreCase = true)
            while (col >= 0) {
                results.add(SearchMatch(row, col, (col + query.length - 1).coerceAtMost(maxCol)))
                col = line.indexOf(query, col + 1, ignoreCase = true)
            }
        }
        return results
    }

    private fun scrollToMatch(match: SearchMatch) {
        val emulator = terminalView.mEmulator ?: return
        val screenRows = emulator.mRows
        val minTopRow = -emulator.getScreen().getActiveTranscriptRows()
        val topRow = (match.row - screenRows + 1).coerceAtLeast(minTopRow)
        terminalView.setTopRow(topRow)
        terminalView.invalidate()
        searchHighlight.setMatches(searchMatches, searchIndex)
    }

    private fun navigateSearch(direction: Int) {
        if (searchMatches.isEmpty()) return
        searchIndex = ((searchIndex + direction) % searchMatches.size + searchMatches.size) % searchMatches.size
        val match = searchMatches[searchIndex]
        findViewById<android.widget.TextView>(R.id.search_count).text = getString(R.string.search_match_count_format, searchIndex + 1, searchMatches.size)
        scrollToMatch(match)
    }

    private fun showSnippetsDialog() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val names = mutableListOf<String>()
        val contents = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            names.add(obj.getString("name"))
            contents.add(obj.getString("content"))
        }

        val items = if (names.isEmpty()) arrayOf("(no snippets — tap + to add)") else names.toTypedArray()

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Snippets")
        builder.setItems(items) { _, which ->
            if (names.isNotEmpty() && which < contents.size) {
                val content = contents[which]
                val session = terminalView.mTermSession ?: return@setItems
                session.write(content.toByteArray(), 0, content.length)
            }
        }
        builder.setPositiveButton("+ Add") { _, _ -> showAddSnippetDialog() }
        builder.setNegativeButton("Edit") { _, _ -> showEditSnippetsDialog() }
        builder.show()
    }

    private fun showAddSnippetDialog() {
        val input = android.widget.EditText(this)
        input.hint = "command or text"
        input.setTextColor(0xFFCDD6F4.toInt())
        input.setHintTextColor(0x66CDD6F4)

        val nameInput = android.widget.EditText(this)
        nameInput.hint = "snippet name"
        nameInput.setTextColor(0xFFCDD6F4.toInt())
        nameInput.setHintTextColor(0x66CDD6F4)

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(48, 16, 48, 16)
        layout.addView(nameInput)
        layout.addView(input)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Add Snippet")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val name = nameInput.text.toString().trim()
                val content = input.text.toString()
                if (name.isNotEmpty() && content.isNotEmpty()) {
                    saveSnippet(name, content)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveSnippet(name: String, content: String) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val obj = org.json.JSONObject()
        obj.put("name", name)
        obj.put("content", content)
        arr.put(obj)
        prefs.edit { putString("snippets", arr.toString()) }
    }

    private fun showEditSnippetsDialog() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val json = prefs.getString("snippets", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            names.add(arr.getJSONObject(i).getString("name"))
        }

        if (names.isEmpty()) {
            android.widget.Toast.makeText(this, "No snippets to edit", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Edit / Delete Snippets")
        builder.setItems(names.toTypedArray()) { _, which ->
            if (which < names.size) {
                android.app.AlertDialog.Builder(this)
                    .setTitle(names[which])
                    .setMessage("What to do with this snippet?")
                    .setPositiveButton("Delete") { _, _ ->
                        arr.remove(which)
                        prefs.edit { putString("snippets", arr.toString()) }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        builder.show()
    }
}
