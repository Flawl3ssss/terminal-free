package com.redtermapp.distro

import android.content.Context
import android.os.Build
import android.util.Log
import com.redtermapp.DnsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class DistroInstaller(private val context: Context) {

    data class Progress(val percent: Int, val speed: String)

    @Volatile
    var cancelled = false

    private var deviceAbi: String = "aarch64"

    fun setDeviceAbi(abi: String) {
        deviceAbi = abi
    }

    fun cancel() {
        cancelled = true
    }

    suspend fun install(
        distro: Distro,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val rootfsDir = getRootfsDir(distro.name)
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val tarball = File(tarballDir(), "${distro.name}.tar.xz")
            if (tarball.exists()) tarball.delete()
            val tarballUrl = distro.tarballUrlFor(deviceAbi)
            Log.i("DistroInstaller", "Downloading $tarballUrl")

            downloadTarball(tarballUrl, tarball, onProgress)
            checkCancel()

            val expectedSha = distro.sha256For(deviceAbi)
            if (expectedSha.isNotEmpty()) {
                verifyChecksum(tarball, expectedSha)
            }
            checkCancel()

            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir, distro)
            saveInstalled(distro.name)
            // The base image is unpacked now - keeping the .tar.xz around
            // would waste ~93 MB of app-private storage forever.
            dropTarball(distro.name)
            Log.i("DistroInstaller", "Install complete for ${distro.name}")
        } catch (e: CancelledException) {
            Log.i("DistroInstaller", "Install cancelled for ${distro.name}")
            cleanup(distro.name)
            throw e
        } catch (e: Throwable) {
            Log.e("DistroInstaller", "Install failed", e)
            cleanup(distro.name)
            throw Exception("Install failed: ${e.message}", e)
        }
    }

    private fun tarballDir(): File =
        File(context.filesDir, "tarballs").apply { mkdirs() }

    fun hasCachedTarball(distroName: String): Boolean =
        File(tarballDir(), "$distroName.tar.xz").exists()

    private fun dropTarball(distroName: String) {
        try { File(tarballDir(), "$distroName.tar.xz").delete() } catch (_: Exception) {}
        try { File(context.cacheDir, "$distroName.tar.xz").delete() } catch (_: Exception) {}
    }

    /**
     * Removes leftover base images (they are useless once the rootfs has been
     * extracted - about 93 MB per distro). Tarballs younger than [maxAgeMs]
     * are kept, which protects a download that is still in progress.
     */
    fun purgeStaleTarballs(maxAgeMs: Long = 10 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        val stale = { f: File ->
            f.isFile && f.name.endsWith(".tar.xz") && now - f.lastModified() > maxAgeMs
        }
        tarballDir().listFiles()?.forEach { f ->
            if (stale(f)) {
                try { f.delete() } catch (_: Exception) {}
            }
        }
        try {
            context.cacheDir.listFiles()?.forEach { f -> if (stale(f)) f.delete() }
        } catch (_: Exception) {}
    }

    /**
     * Restores a distro to its freshly extracted state: wipes installed
     * packages, caches and shell configs. Uses the cached base tarball when
     * available, otherwise falls back to a fresh download.
     */
    suspend fun resetToDefault(
        distroName: String,
        onProgress: (Progress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        cancelled = false
        val distro = com.redtermapp.distro.DistroRegistry.allDistros
            .firstOrNull { it.name == distroName }
        if (distro == null) return@withContext false
        try {
            val rootfsDir = getRootfsDir(distroName)
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val tarball = File(tarballDir(), "$distroName.tar.xz")
            if (!tarball.exists()) {
                install(distro, onProgress)
                return@withContext true
            }
            extractTarball(tarball, rootfsDir, onProgress)
            checkCancel()
            fixupDirectoryPermissions(rootfsDir)
            setupRootfs(rootfsDir, distro)
            saveInstalled(distroName)
            dropTarball(distroName)
            Log.i("DistroInstaller", "Reset complete for $distroName")
            true
        } catch (e: CancelledException) {
            cleanup(distroName)
            false
        } catch (e: Throwable) {
            Log.e("DistroInstaller", "Reset failed", e)
            cleanup(distroName)
            throw Exception("Reset failed: ${e.message}", e)
        }
    }

    class CancelledException : Exception("Installation cancelled")

    private fun checkCancel() {
        if (cancelled) throw CancelledException()
    }

    private fun cleanup(distroName: String) {
        try {
            getRootfsDir(distroName).deleteRecursively()
        } catch (_: Exception) {}
        try {
            File(tarballDir(), "$distroName.tar.xz").delete()
        } catch (_: Exception) {}
        try {
            File(context.cacheDir, "${distroName}.tar.xz").delete()
        } catch (_: Exception) {}
        try {
            File(context.filesDir, "installed/$distroName").delete()
        } catch (_: Exception) {}
    }

    private suspend fun downloadTarball(
        urlString: String,
        dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val httpUrl = URL(urlString)
        val conn = httpUrl.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.connect()

        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            throw Exception("HTTP $responseCode for $urlString")
        }

        val total = conn.contentLengthLong
        val buffer = ByteArray(8192)

        FileOutputStream(dest).use { output ->
            conn.inputStream.use { input ->
                var read: Int
                var downloaded = 0L
                val startTime = System.currentTimeMillis()

                while (input.read(buffer).also { read = it } != -1) {
                    checkCancel()
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val percent = ((downloaded * 100) / total).toInt()
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val speed = if (elapsed > 0) {
                            "${(downloaded / 1024 / elapsed)} KB/s"
                        } else "0 KB/s"
                        onProgress(Progress(percent, speed))
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun verifyChecksum(file: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256.lowercase()) {
            throw Exception("SHA-256 mismatch: expected $expectedSha256, got $actual")
        }
    }

    private fun getNativeXz(): File? {
        val abis = android.os.Build.SUPPORTED_64_BIT_ABIS
        if (abis.isEmpty() || abis[0] != "arm64-v8a") return null
        val xzDir = File(context.codeCacheDir, "xz")
        val xzBin = File(xzDir, "xz")
        val xzLib = File(xzDir, "liblzma.so.5")
        if (xzBin.canExecute() && xzLib.canRead()) return xzBin
        try {
            xzDir.mkdirs()
            context.assets.open("xz/lib/liblzma.so.5").use { input ->
                FileOutputStream(xzLib).use { input.copyTo(it) }
            }
            xzLib.setReadable(true, true)
            context.assets.open("xz/bin/xz").use { input ->
                FileOutputStream(xzBin).use { input.copyTo(it) }
            }
            xzBin.setReadable(true, true)
            xzBin.setExecutable(true, true)
            if (xzBin.canExecute()) return xzBin
        } catch (e: Exception) {
            Log.w("DistroInstaller", "Native xz not available", e)
            xzBin.delete()
            xzLib.delete()
        }
        return null
    }

    private suspend fun extractTarball(
        tarball: File,
        dest: File,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val nativeXz = getNativeXz()
        if (nativeXz != null) {
            try {
                extractWithNativeXz(nativeXz, tarball, dest, onProgress)
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                Log.w("DistroInstaller", "Native xz failed, falling back to Java", e)
                extractWithJavaXz(tarball, dest, onProgress)
            }
        } else {
            extractWithJavaXz(tarball, dest, onProgress)
        }
    }

    private fun extractWithNativeXz(
        xzBin: File, tarball: File, dest: File,
        onProgress: (Progress) -> Unit
    ) {
        val pb = ProcessBuilder(xzBin.absolutePath, "-dc", tarball.absolutePath)
        pb.environment()["LD_LIBRARY_PATH"] = xzBin.parentFile!!.absolutePath
        val process = pb.start()
        try {
            process.inputStream.use { input ->
                BufferedInputStream(input, 65536).use { bis ->
                    TarArchiveInputStream(bis).use { tarIn ->
                        extractTarEntries(tarIn, dest, tarball.length(), onProgress)
                    }
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw Exception("Native xz decompressor failed (exit $exitCode), falling back")
            }
        } catch (e: CancelledException) {
            killProcess(process)
            throw e
        } catch (e: Exception) {
            killProcess(process)
            throw e
        }
    }

    private fun killProcess(process: Process) {
        process.destroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        }
    }

    private fun extractWithJavaXz(
        tarball: File, dest: File,
        onProgress: (Progress) -> Unit
    ) {
        try {
            val total = tarball.length()
            var extracted = 0L
            FileInputStream(tarball).use { fis ->
                XZCompressorInputStream(fis).use { xzIn ->
                    BufferedInputStream(xzIn, 65536).use { bis ->
                        TarArchiveInputStream(bis).use { tarIn ->
                            extractTarEntries(tarIn, dest, total, onProgress)
                        }
                    }
                }
            }
        } catch (e: NoClassDefFoundError) {
            throw Exception("Missing compression library: ${e.message}")
        }
    }

    private fun extractTarEntries(
        tarIn: TarArchiveInputStream, dest: File,
        totalCompressed: Long, onProgress: (Progress) -> Unit
    ) {
        val firstEntry = tarIn.getNextEntry()
        var prefixToStrip = ""
        if (firstEntry != null) {
            val name = firstEntry.name
            val slash = name.indexOf('/')
            if (slash > 0) {
                prefixToStrip = name.substring(0, slash + 1)
                Log.i("DistroInstaller", "Stripping prefix: $prefixToStrip")
            }
        }
        var processed = 0L
        var entryCount = 0
        fun processEntry(entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry) {
            var entryName = entry.name
            if (prefixToStrip.isNotEmpty() && entryName.startsWith(prefixToStrip)) {
                entryName = entryName.removePrefix(prefixToStrip)
            }
            if (entryName.isEmpty()) return
            val target = File(dest, entryName)
            if (entry.isSymbolicLink) {
                val linkTarget = entry.linkName
                target.parentFile?.mkdirs()
                try {
                    target.delete()
                    android.system.Os.symlink(linkTarget, target.absolutePath)
                } catch (e: Exception) {
                    Log.w("DistroInstaller", "Symlink failed ${entry.name}: ${e.message}")
                }
            } else if (entry.isLink) {
                // Hardlink (tar type '1'): the entry carries NO data - the
                // target file must already be in the stream. Writing it as a
                // regular file produced EMPTY binaries (/usr/bin/env, perl and
                // every rust-coreutils applet), which broke exec with
                // "Exec format error". Re-link to the already-extracted file.
                var linkName = entry.linkName
                if (prefixToStrip.isNotEmpty() && linkName.startsWith(prefixToStrip)) {
                    linkName = linkName.removePrefix(prefixToStrip)
                }
                linkName = linkName.removePrefix("./").removePrefix("/")
                val src = File(dest, linkName)
                target.parentFile?.mkdirs()
                target.delete()
                if (src.isFile && src.length() > 0L) {
                    try {
                        android.system.Os.link(src.absolutePath, target.absolutePath)
                    } catch (e: Exception) {
                        // NEVER fall back to a full copy: 114 applets x 10.6 MB
                        // = 1.19 GB of real duplicates (link() does get
                        // rejected on device). A symlink keeps argv[0]
                        // dispatch intact and costs a few bytes.
                        try {
                            val rel = src.relativeTo(target.parentFile ?: dest).path
                            android.system.Os.symlink(rel, target.absolutePath)
                        } catch (e2: Exception) {
                            Log.w("DistroInstaller", "Hardlink failed ${entry.name}: link=${e.message} sym=${e2.message}")
                        }
                    }
                } else {
                    Log.w("DistroInstaller", "Hardlink target missing for ${entry.name}: $linkName")
                }
            } else if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(65536)
                    while (true) {
                        val read = tarIn.read(buf)
                        if (read == -1) break
                        checkCancel()
                        out.write(buf, 0, read)
                        processed += read
                    }
                }
                val perm = entry.mode and 0x1FF
                val isExec = (perm and 0b001001001) != 0
                target.setReadable(true, true)
                target.setExecutable(isExec, true)
                target.setWritable(true, true)
            }
        }
        if (firstEntry != null) processEntry(firstEntry)
        var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? = tarIn.getNextEntry()
        while (entry != null) {
            checkCancel()
            processEntry(entry)
            entryCount++
            val pct = if (totalCompressed > 0) {
                ((processed * 100L) / (totalCompressed * 3L)).toInt().coerceAtMost(99)
            } else 0
            onProgress(Progress(pct, "Extracting"))
            entry = tarIn.getNextEntry()
        }
    }

    private fun writeResolvConf(rootfs: File) {
        val resolv = File(rootfs, "etc/resolv.conf")
        resolv.parentFile?.mkdirs()
        safeWriteText(resolv, DnsHelper.resolvConfText(context))
    }

    private fun ensureSupplementaryGroups(rootfs: File) {
        val group = File(rootfs, "etc/group")
        group.parentFile?.mkdirs()
        val existing = if (group.exists()) group.readText() else ""
        val sb = StringBuilder(existing)
        val baseEntries = listOf(
            "root:x:0:root", "wheel:x:0:root",
            "inet:x:3003:", "everybody:x:9997:"
        )
        for (entry in baseEntries) {
            val name = entry.substringBefore(':')
            if (!existing.contains(":$name")) {
                if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append('\n')
                sb.append(entry).append('\n')
            }
        }
        try {
            val status = java.io.File("/proc/self/status").readLines()
            val groupsLine = status.firstOrNull { it.startsWith("Groups:") } ?: return
            val gids = groupsLine.removePrefix("Groups:").trim().split("\\s+".toRegex())
            for (gidStr in gids) {
                val gid = gidStr.toIntOrNull() ?: continue
                if (gid <= 0) continue
                val name = "android_$gid"
                if (!existing.contains(":$name:")) {
                    if (!sb.endsWith('\n')) sb.append('\n')
                    sb.append("$name:x:$gid:\n")
                }
            }
        } catch (_: Exception) {}
        safeWriteText(group, sb.toString())
    }

    private fun ensureWritable(file: File) {
        if (file.exists()) file.setWritable(true, true)
        file.parentFile?.let { if (!it.canWrite()) it.setWritable(true, true) }
    }

    private fun safeWriteText(file: File, text: String) {
        ensureWritable(file)
        file.writeText(text)
    }

    private fun safeAppendText(file: File, text: String) {
        ensureWritable(file)
        file.appendText(text)
    }

    private fun setupRootfs(rootfs: File, distro: Distro) {
        val uid = android.os.Process.myUid()
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
        }
        writeResolvConf(rootfs)
        val fstab = File(rootfs, "etc/fstab")
        if (!fstab.exists()) {
            safeWriteText(fstab, "none /proc proc defaults 0 0\nnone /sys sysfs defaults 0 0\n")
        }
        createDeviceNodes(rootfs)
        repairRootfs(rootfs)
    }

    private fun fixupDirectoryPermissions(rootfs: File) {
        rootfs.walkTopDown().filter { it.isDirectory }.forEach { d ->
            d.setReadable(true, true)
            d.setExecutable(true, true)
            d.setWritable(true, true)
        }
    }

    /**
     * Heals installs extracted by older app versions: tar hardlinks (type '1')
     * used to be written as EMPTY files - the rust-coreutils multicall
     * applets (all hardlinks of csplit) and /usr/bin/perl came out 0 bytes,
     * so /usr/bin/env failed with "Exec format error" and perl postinst
     * scripts fell back to being parsed by dash. Re-links anything that is
     * still an empty file where a populated target is known.
     */
    private fun repairEmptyHardlinks(rootfs: File): Int {
        var fixed = 0
        fun relink(empty: File, src: File) {
            if (!empty.isFile || empty.length() != 0L) return
            if (!src.isFile || src.length() == 0L) return
            try {
                empty.delete()
                android.system.Os.link(src.absolutePath, empty.absolutePath)
                fixed++
            } catch (e: Exception) {
                Log.w("DistroInstaller", "relink ${empty.name}: link failed (${e.message}), trying symlink")
                try {
                    empty.delete()
                    val rel = src.relativeTo(empty.parentFile ?: src.parentFile).path
                    android.system.Os.symlink(rel, empty.absolutePath)
                    fixed++
                } catch (_: Exception) {
                }
            }
        }

        // 1) rust-coreutils: every applet under cargo/bin/coreutils is a
        //    hardlink of the single multicall binary (csplit in our archive).
        val coreutilsDir = File(rootfs, "usr/lib/cargo/bin/coreutils")
        val csplit = File(coreutilsDir, "csplit")
        if (coreutilsDir.isDirectory && csplit.isFile && csplit.length() > 0L) {
            coreutilsDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name != "csplit" && f.length() == 0L) relink(f, csplit)
            }
        }

        // 2) The multicall alias and perl are hardlinks as well.
        relink(File(rootfs, "usr/bin/coreutils"), csplit)
        relink(File(rootfs, "usr/bin/perl"), File(rootfs, "usr/bin/perl5.40.1"))
        return fixed
    }

    fun repairRootfs(rootfs: File): String {
        val repairs = mutableListOf<String>()
        val uid = android.os.Process.myUid()

        if (!File(rootfs, ".perms_fixed").exists()) {
            fixupDirectoryPermissions(rootfs)
            try {
                File(rootfs, ".perms_fixed").writeText("1")
            } catch (_: Exception) {}
            repairs.add("Fixed directory permissions")
        }

        // v1.3.3: link() was rejected on device and both fallbacks (the
        // extractor and repairEmptyHardlinks below) silently copied whole
        // files - 115 multicall applets became 1.19 GB of REAL duplicates
        // (/usr = 1.44 GB instead of ~270 MB). Collapse byte-identical
        // copies in the fixed paths first, then let relink/flatten work.
        val collapsed = FsUtil.collapseDuplicates(rootfs)
        if (collapsed > 0) {
            repairs.add("Collapsed $collapsed duplicate binaries")
            try { File(rootfs, ".tf_links_flat").delete() } catch (_: Exception) {}
        }

        val relinked = repairEmptyHardlinks(rootfs)
        if (relinked > 0) {
            repairs.add("Repaired $relinked broken hardlinks")
        }

        // v1.3.1: every walker (our size label, du in proot, Android
        // Settings) counts each hardlink as a full copy - 114 links of one
        // 10.6 MB rust-coreutils file read as ~1.15 GB that is not on disk.
        // Swap links for symlinks: the inode stays shared, argv[0] dispatch
        // (coreutils/perl) is unaffected, and every size report becomes
        // honest. One-time per rootfs; re-armed if a relink ever happens.
        val flatMarker = File(rootfs, ".tf_links_flat")
        if (relinked > 0) {
            try { flatMarker.delete() } catch (_: Exception) {}
        }
        if (!flatMarker.exists()) {
            val flattened = FsUtil.flattenHardlinks(rootfs)
            try { flatMarker.writeText("1") } catch (_: Exception) {}
            if (flattened > 0) {
                repairs.add("Converted $flattened hardlinks to symlinks")
            }
        }

        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.exists() || !passwd.readText().contains(":$uid:")) {
            passwd.parentFile?.mkdirs()
            safeAppendText(passwd, "root:x:$uid:0:root:/root:/bin/sh\n")
            repairs.add("Added passwd entry for uid $uid")
        }
        ensureSupplementaryGroups(rootfs)
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || !hosts.readText().contains("127.0.0.1")) {
            hosts.parentFile?.mkdirs()
            safeWriteText(hosts, "127.0.0.1 localhost\n::1 localhost\n")
            repairs.add("Created /etc/hosts")
        }

        File(rootfs, "root").mkdirs()
        repairs.add("Created /root")

        val subdirs = rootfs.listFiles()?.filter { it.isDirectory && it.name.contains('-') } ?: emptyList()
        for (subdir in subdirs) {
            val innerBin = File(subdir, "bin")
            if (innerBin.exists()) {
                repairs.add("Found nested rootfs in ${subdir.name}/, migrating...")
                subdir.listFiles()?.forEach { file ->
                    val dest = File(rootfs, file.name)
                    if (file.isDirectory) {
                        file.copyRecursively(dest, overwrite = true)
                        file.deleteRecursively()
                    } else {
                        file.copyTo(dest, overwrite = true)
                        file.delete()
                    }
                }
                repairs.add("Migrated files from ${subdir.name}/ to rootfs")
            }
        }

        val busybox = File(rootfs, "bin/busybox")
        if (busybox.exists()) {
            if (!busybox.canExecute()) {
                busybox.setExecutable(true, true)
                repairs.add("Made bin/busybox executable")
            }
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                sh.delete()
                try {
                    android.system.Os.symlink("busybox", sh.absolutePath)
                } catch (_: Exception) {
                    busybox.copyTo(sh, overwrite = true)
                    sh.setExecutable(true, true)
                    repairs.add("Copied bin/busybox -> bin/sh")
                }
                if (sh.canExecute()) {
                    repairs.add("bin/sh is now executable")
                } else {
                    repairs.add("WARN: bin/sh still not executable")
                }
            }
        } else {
            repairs.add("WARN: bin/busybox not found in rootfs")
            val binDir = File(rootfs, "bin")
            if (binDir.exists()) {
                val contents = binDir.list()?.joinToString(", ") ?: "empty"
                repairs.add("bin/ contents: $contents")
            } else {
                repairs.add("bin/ directory missing!")
            }
        }

        val resolv = File(rootfs, "etc/resolv.conf")
        val expectedDns = DnsHelper.resolvConfText(context)
        val currentDns = try {
            if (resolv.exists()) resolv.readText() else null
        } catch (_: Exception) {
            null
        }
        when {
            currentDns == null -> {
                writeResolvConf(rootfs)
                repairs.add("Created etc/resolv.conf with Android DNS")
            }
            currentDns != expectedDns -> {
                writeResolvConf(rootfs)
                repairs.add("Updated etc/resolv.conf (Android DNS + resolver options)")
            }
        }

        return repairs.joinToString("\n")
    }

    fun isInstalled(distroName: String): Boolean =
        File(context.filesDir, "installed/${distroName}").exists()

    fun getRootfsDir(distroName: String): File =
        File(context.filesDir, "rootfs/$distroName")

    fun saveInstalled(distroName: String) {
        File(context.filesDir, "installed").mkdirs()
        File(context.filesDir, "installed/$distroName").writeText(distroName)
    }

    fun getInstalledDistros(): List<String> {
        val dir = File(context.filesDir, "installed")
        return if (dir.exists()) dir.list()?.toList() ?: emptyList() else emptyList()
    }

    fun uninstall(distroName: String) {
        getRootfsDir(distroName).deleteRecursively()
        File(context.filesDir, "installed/$distroName").delete()
        File(tarballDir(), "$distroName.tar.xz").delete()
        File(context.cacheDir, "${distroName}.tar.xz").delete()
    }

    fun detectDistro(rootfsDir: File): String {
        val osRelease = try { File(rootfsDir, "etc/os-release").readText() } catch (_: Exception) { "" }
        return when {
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
    }

    private fun createDeviceNodes(rootfs: File) {
        val devDir = File(rootfs, "dev")
        devDir.mkdirs()
        for (dev in listOf("null", "zero", "random", "urandom")) {
            val f = File(devDir, dev)
            if (!f.exists()) {
                try {
                    f.writeText("")
                } catch (_: Exception) {}
            }
        }
    }
}
