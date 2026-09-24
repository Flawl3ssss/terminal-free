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

    /**
     * Replaces byte-identical COPIES with symlinks where the image is
     * *defined* to consist of one file: the rust-coreutils multicall
     * applets (all copies of csplit) and the coreutils/perl aliases.
     *
     * link() was rejected on device and both fallbacks (the extractor and
     * the empty-hardlink repair) silently did src.copyTo(), so 115 applets
     * became 1.19 GB of REAL duplicates - which is why du, the app's own
     * label and Android Settings all honestly agreed on ~1.9 GB.
     *
     * Only fixed, well-defined paths are touched, and "identical" means
     * equal size plus an equal 8 KB head sample - inside these paths every
     * file is the same binary by construction. Cheap to re-run: after the
     * first pass everything is a symlink and gets skipped.
     *
     * @return how many copies were collapsed.
     */
    fun collapseDuplicates(root: File): Int {
        var collapsed = 0
        val dir = File(root, "usr/lib/cargo/bin/coreutils")
        val canonical = File(dir, "csplit")
        if (dir.isDirectory && canonical.isFile && canonical.length() > 0L) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.name == "csplit" || !f.isFile || isSymlink(f)) continue
                if (looksIdentical(f, canonical)) collapsed += replaceWithSymlink(f, canonical)
            }
        }
        val bin = File(root, "usr/bin")
        val coreutilsAlias = File(bin, "coreutils")
        if (coreutilsAlias.isFile && !isSymlink(coreutilsAlias) &&
            canonical.isFile && looksIdentical(coreutilsAlias, canonical)
        ) {
            collapsed += replaceWithSymlink(coreutilsAlias, canonical)
        }
        val perl = File(bin, "perl5.40.1")
        val perlAlias = File(bin, "perl")
        if (perlAlias.isFile && !isSymlink(perlAlias) &&
            perl.isFile && looksIdentical(perlAlias, perl)
        ) {
            collapsed += replaceWithSymlink(perlAlias, perl)
        }
        return collapsed
    }

    /** Same size + first 8 KB equal - decisive inside these fixed paths. */
    private fun looksIdentical(a: File, b: File): Boolean {
        if (a.length() != b.length() || a.length() == 0L) return false
        return try {
            val ba = ByteArray(8192)
            val bb = ByteArray(8192)
            val ra = a.inputStream().use { it.read(ba) }
            val rb = b.inputStream().use { it.read(bb) }
            ra == rb && ra > 0 && ba.copyOf(ra).contentEquals(bb.copyOf(rb))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Swaps [f] for a symlink to [canonical] (relative target - valid both
     * in the guest and when the app walks the tree from the host). If even
     * the symlink cannot be created, an empty placeholder is left behind so
     * repairEmptyHardlinks() can heal the name on the next session instead
     * of it vanishing for good.
     */
    private fun replaceWithSymlink(f: File, canonical: File): Int {
        val parent = f.parentFile ?: return 0
        return try {
            if (!f.delete()) return 0
            Os.symlink(canonical.relativeTo(parent).path, f.absolutePath)
            1
        } catch (_: Exception) {
            try {
                Os.link(canonical.absolutePath, f.absolutePath)
            } catch (_: Exception) {
                try { f.createNewFile() } catch (_: Exception) {}
            }
            0
        }
    }
}
