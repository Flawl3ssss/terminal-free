package com.redtermapp.distro

import android.system.Os
import java.io.File

/**
 * Filesystem helpers that stay honest about hardlinks.
 *
 * Every size walker in the stack - the app's own "distro size" label, `du`
 * inside proot, and Android's per-app storage stats - reports each hardlink
 * as a full copy of the data. The image ships 114 hardlinks of one 10.6 MB
 * rust-coreutils multicall binary, so all of them claimed ~1.17 GB that does
 * not exist on disk (measured on device: du said 1 178 775 KB where
 * 114 x 10 330 KB = 1 177 620 KB - a 0.1 % match, and Android Settings then
 * displayed "2 GB" for a container that really uses 812 MB).
 *
 * Two defences: count by inode where we do the maths ourselves, and flatten
 * hardlinks into symlinks where other processes do the maths.
 */
object FsUtil {

    /** readlink() succeeds only for symlinks - avoids trusting st_mode. */
    private fun isSymlink(f: File): Boolean = try {
        Os.readlink(f.absolutePath)
        true
    } catch (_: Exception) {
        false
    }

    private fun statOrNull(f: File) = try {
        Os.stat(f.absolutePath)
    } catch (_: Exception) {
        null
    }

    /**
     * Total bytes under [root]: each inode counted once, directory symlinks
     * never followed (a /bin -> /usr/bin link would otherwise double every
     * file behind it).
     */
    fun uniqueSize(root: File): Long {
        val seen = HashSet<String>()
        var total = 0L
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (isSymlink(c)) continue // a few bytes; target counted at its real path
                if (c.isDirectory) {
                    visit(c)
                } else if (c.isFile) {
                    val st = statOrNull(c)
                    if (st == null) {
                        total += c.length()
                    } else if (st.st_nlink <= 1L || seen.add("${st.st_dev}:${st.st_ino}")) {
                        total += st.st_size
                    }
                }
            }
        }
        visit(root)
        return total
    }

    /**
     * Replaces every hardlink with a symlink to the path that stays.
     *
     * Programs dispatching on argv[0] (rust-coreutils multicall, perl)
     * behave identically through either, but symlinks are reported as a few
     * bytes by *every* walker - including Android Settings, which we cannot
     * patch. Data is never copied: the inode survives at [prev] paths, and
     * if the swap ever fails the hardlink is recreated.
     *
     * @return how many links were converted.
     */
    fun flattenHardlinks(root: File): Int {
        val first = HashMap<String, File>()
        var converted = 0
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (c in children) {
                if (isSymlink(c)) continue
                if (c.isDirectory) {
                    visit(c)
                    continue
                }
                if (!c.isFile) continue
                val st = statOrNull(c) ?: continue
                if (st.st_nlink <= 1L) continue
                val key = "${st.st_dev}:${st.st_ino}"
                val prev = first[key]
                if (prev == null) {
                    first[key] = c
                    continue
                }
                val guestTarget = "/" + prev.relativeTo(root).path
                try {
                    if (!c.delete()) continue
                    Os.symlink(guestTarget, c.absolutePath)
                    converted++
                } catch (_: Exception) {
                    // Roll back: keep the hardlink rather than lose the file.
                    try {
                        Os.link(prev.absolutePath, c.absolutePath)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        visit(root)
        return converted
    }
}
