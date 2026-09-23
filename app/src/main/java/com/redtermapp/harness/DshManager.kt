package com.redtermapp.harness

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.redtermapp.distro.DistroInstaller
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Manages the DeepSeek Harness (dsh) inside the proot container:
 *  - workspace paths (host side: filesDir/workspace, guest side: /workspace)
 *  - the DeepSeek API key ($DSH_HOME/.env layer, lowest precedence so keys
 *    saved through the dsh Web UI still win)
 *  - starting/stopping the dsh Web UI (http://127.0.0.1:3080)
 *  - the one-button update with backup/rollback
 *
 * Callbacks are always delivered on the main thread.
 */
object DshManager {

    const val WEB_PORT = 3080
    const val WEB_URL = "http://127.0.0.1:$WEB_PORT"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var webProcess: Process? = null

    // ---------------------------------------------------------------- paths

    fun workspaceDir(ctx: Context): File =
        File(ctx.filesDir, "workspace").apply { mkdirs() }

    fun tfDir(ctx: Context): File =
        File(workspaceDir(ctx), ".tf").apply { mkdirs() }

    fun dshHomeDir(ctx: Context): File =
        File(workspaceDir(ctx), ".dsh").apply { mkdirs() }

    fun versionFile(ctx: Context): File = File(tfDir(ctx), "dsh_version")

    fun envFile(ctx: Context): File = File(dshHomeDir(ctx), ".env")

    fun readVersion(ctx: Context): String? =
        versionFile(ctx).takeIf { it.exists() }
            ?.readText()?.trim()?.lineSequence()?.firstOrNull()
            ?.takeIf { it.isNotEmpty() }

    // -------------------------------------------------------------- api key

    fun readApiKey(ctx: Context): String? {
        val f = envFile(ctx)
        if (!f.exists()) return null
        return f.readLines()
            .firstOrNull { it.trimStart().startsWith("DEEPSEEK_API_KEY=") }
            ?.substringAfter('=')?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** Saves the key into $DSH_HOME/.env (merging with other lines), or removes it when blank. */
    fun saveApiKey(ctx: Context, key: String) {
        val f = envFile(ctx)
        f.parentFile?.mkdirs()
        val other = if (f.exists()) f.readLines().filter {
            val t = it.trimStart()
            t.isNotEmpty() && !t.startsWith("DEEPSEEK_API_KEY=")
        } else emptyList()
        val lines = if (key.isEmpty()) other else other + "DEEPSEEK_API_KEY=$key"
        if (lines.isEmpty()) {
            f.delete()
            return
        }
        f.writeText(lines.joinToString("\n") + "\n")
        // Best-effort 0600 on top of the already app-private filesDir.
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
    }

    // ------------------------------------------------------------- web server

    fun isWebRunning(): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress("127.0.0.1", WEB_PORT), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun activeRootfs(ctx: Context): Pair<String, File>? {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val installer = DistroInstaller(ctx)
        val installed = installer.getInstalledDistros()
        val name = prefs.getString("last_distro", null)?.takeIf { installed.contains(it) }
            ?: installed.firstOrNull()
            ?: return null
        return name to installer.getRootfsDir(name)
    }

    /** Builds a host-side launch script that execs proot with the shared bind set. */
    private fun hostScript(ctx: Context, rootfsDir: File, workdir: String, guestCmd: String): String {
        val nativeLibDir = ctx.applicationInfo.nativeLibraryDir
        val prootBin = "$nativeLibDir/libproot.so"
        val prootLoader = "$nativeLibDir/libproot-loader.so"
        val prootLoader32 = "$nativeLibDir/libproot-loader32.so"
        val ldr32 = if (File(prootLoader32).exists()) "export PROOT_LOADER_32=$prootLoader32\n" else ""
        val rp = rootfsDir.absolutePath
        val ws = workspaceDir(ctx).absolutePath
        return """#!/system/bin/sh
export HOME=/root
export PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export DSH_HOME=/workspace/.dsh
export PROOT_LOADER=$prootLoader
${ldr32}export PROOT_TMP_DIR=$rp/tmp
mkdir -p "$rp/tmp"
WS="$ws"
mkdir -p "${'$'}WS" "${'$'}WS/.dsh" "${'$'}WS/.tf"
exec $prootBin -0 -L -r "$rp" -w $workdir --link2symlink --sysvipc --kill-on-exit \
    -b /dev -b /proc -b /sys -b /system -b /apex -b /linkerconfig/ld.config.txt \
    -b "${'$'}WS:/workspace" \
    $guestCmd 2>&1
"""
    }

    private fun logTail(f: File, max: Int = 1500): String = try {
        if (!f.exists()) "(log is empty)" else f.readText().takeLast(max)
    } catch (_: Exception) {
        "(log is empty)"
    }

    /**
     * Ensures the dsh Web UI listens on 127.0.0.1:3080, starting it if needed.
     * Callback: (true, WEB_URL) or (false, error/log tail).
     */
    fun ensureWebServer(ctx: Context, callback: (Boolean, String) -> Unit) {
        Thread {
            if (isWebRunning()) {
                mainHandler.post { callback(true, WEB_URL) }
                return@Thread
            }
            val rf = activeRootfs(ctx)
            if (rf == null) {
                mainHandler.post { callback(false, "No distro is installed yet.") }
                return@Thread
            }
            val (name, rootfsDir) = rf
            if (!rootfsDir.exists()) {
                mainHandler.post { callback(false, "Distro $name is not installed.") }
                return@Thread
            }
            if (readVersion(ctx) == null) {
                mainHandler.post {
                    callback(
                        false,
                        "dsh is not installed yet.\nOpen a terminal once so the first-time setup can finish, then try again."
                    )
                }
                return@Thread
            }
            try {
                val script = File(ctx.filesDir, "dsh-web.sh")
                script.writeText(
                    hostScript(
                        ctx, rootfsDir, "/workspace",
                        "/system/bin/sh -c 'exec dsh web --no-open --port $WEB_PORT'"
                    )
                )
                script.setExecutable(true, true)
                val logFile = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "dsh-web.log")
                val proc = ProcessBuilder("/system/bin/sh", script.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .start()
                webProcess = proc
                repeat(180) { // up to 90 s: first boot initializes the dsh profile
                    Thread.sleep(500)
                    if (isWebRunning()) {
                        mainHandler.post { callback(true, WEB_URL) }
                        return@Thread
                    }
                    if (!proc.isAlive) {
                        mainHandler.post {
                            callback(false, "The dsh Web UI exited early:\n" + logTail(logFile))
                        }
                        return@Thread
                    }
                }
                mainHandler.post {
                    callback(false, "Timed out waiting for the dsh Web UI:\n" + logTail(logFile))
                }
            } catch (e: Exception) {
                mainHandler.post { callback(false, "Error: ${e.message}") }
            }
        }.start()
    }

    // --------------------------------------------------------------- update

    /**
     * Installs/updates @deepseek-ai/dsh to the latest version with backup and
     * rollback. Config, plugins and sessions live under $DSH_HOME (/workspace/.dsh)
     * which is preserved; the npm package is rolled back if the new version fails.
     */
    fun update(ctx: Context, onLine: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            val rf = activeRootfs(ctx)
            if (rf == null) {
                mainHandler.post { onDone(false, "No distro is installed yet.") }
                return@Thread
            }
            val (_, rootfsDir) = rf
            if (!rootfsDir.exists()) {
                mainHandler.post { onDone(false, "The distro rootfs is not installed.") }
                return@Thread
            }
            // A running Web UI must not sit on the files being replaced.
            webProcess?.let { try { it.destroy() } catch (_: Exception) {} }
            webProcess = null
            try {
                val rootDir = File(rootfsDir, "root").apply { mkdirs() }
                val updateSh = File(rootDir, ".tf_update.sh")
                updateSh.writeText(UPDATE_SH)
                updateSh.setExecutable(true, true)
                val runner = File(ctx.filesDir, "dsh-update.sh")
                runner.writeText(hostScript(ctx, rootfsDir, "/workspace", "/bin/bash /root/.tf_update.sh"))
                runner.setExecutable(true, true)
                val proc = ProcessBuilder("/system/bin/sh", runner.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        mainHandler.post { onLine(line) }
                    }
                }
                val code = proc.waitFor()
                mainHandler.post { onDone(code == 0, "Finished (exit code $code)") }
            } catch (e: Exception) {
                mainHandler.post { onDone(false, "Error: ${e.message}") }
            }
        }.start()
    }

    // Guest-side update script (runs as /bin/bash /root/.tf_update.sh).
    // NOTE: every shell "$" is written as ${'$'} so Kotlin does not treat it
    // as a string template.
    private val UPDATE_SH = """#!/bin/bash
set -u
cd /workspace || { echo "RESULT: FAIL - /workspace is not available"; exit 3; }
TF=/workspace/.tf
BK=${'$'}TF/backup
mkdir -p "${'$'}TF" "${'$'}BK"
echo "== dsh update: ${'$'}(date '+%F %T') =="
if ! command -v npm >/dev/null 2>&1; then
    echo "ERR: npm not found - open a terminal first to finish the initial setup."
    exit 3
fi
if pkill -f "dsh web" 2>/dev/null; then
    sleep 1
    echo "Stopped a running dsh Web server."
fi
prev="${'$'}(npm ls -g --depth=0 2>/dev/null | sed -n 's/.*@deepseek-ai\/dsh@//p' | awk '{print ${'$'}1}')"
echo "Installed version: ${'$'}{prev:-<none>}"
echo "-- Backing up config, sessions and plugins (${'$'}DSH_HOME)..."
if tar -C /workspace -czf "${'$'}BK/dsh-home.tar.gz" .dsh 2>/dev/null; then
    echo "Backup OK: ${'$'}BK/dsh-home.tar.gz (${'$'}(du -h "${'$'}BK/dsh-home.tar.gz" | awk '{print ${'$'}1}'))"
else
    echo "WARN: could not create the backup archive (continuing; npm does not touch ${'$'}DSH_HOME)"
fi
echo "${'$'}{prev:-}" > "${'$'}BK/prev-version.txt"
echo "-- Installing the latest @deepseek-ai/dsh ..."
if npm install -g --no-fund --no-audit @deepseek-ai/dsh@latest; then
    new="${'$'}(dsh --version 2>/dev/null | head -1)"
    echo "New version: ${'$'}{new:-unknown}"
    if dsh --help >/dev/null 2>&1; then
        echo "${'$'}{new:-unknown}" > "${'$'}TF/dsh_version"
        echo "RESULT: OK - updated ${'$'}{prev:-<none>} -> ${'$'}{new:-unknown}. Config, plugins and sessions preserved."
        exit 0
    fi
    echo "FAIL: the new version does not start."
else
    echo "FAIL: npm install failed."
fi
echo "-- Rolling back..."
if [ -n "${'$'}{prev:-}" ]; then
    if npm install -g --no-fund --no-audit "@deepseek-ai/dsh@${'$'}prev"; then
        echo "Rolled back to ${'$'}prev"
    else
        echo "WARN: rollback install failed (the network may be down)"
    fi
else
    echo "Nothing to roll back (dsh was not installed)."
fi
if [ -f "${'$'}BK/dsh-home.tar.gz" ]; then
    tar -C /workspace -xzf "${'$'}BK/dsh-home.tar.gz" 2>/dev/null && echo "Config/sessions restored from the backup."
fi
dsh --version 2>/dev/null | head -1 > "${'$'}TF/dsh_version"
echo "RESULT: FAIL - the update failed; the previous version was restored."
exit 1
"""
}
