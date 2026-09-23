package com.redtermapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.redtermapp.R
import java.io.File

/**
 * Two-way file bridge between the device and the container's /workspace.
 *
 * Workspace tab: browses filesDir/workspace (host-backed, bind-mounted to
 * /workspace inside proot).
 *
 * Device tab: browses the device storage DIRECTLY with plain java.io.File -
 * every folder and every file, no folder picking. For that the app requests
 * full file access: "All files access" (MANAGE_EXTERNAL_STORAGE) on API 30+,
 * READ/WRITE_EXTERNAL_STORAGE below. The proot container itself still has
 * zero access to any device path; files cross the boundary only through this
 * screen (import into /workspace / export out of it).
 */
class FilesActivity : AppCompatActivity() {

    private enum class Tab { WORKSPACE, DEVICE }

    private data class Entry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val file: File? = null
    )

    private lateinit var wsRoot: File
    private lateinit var internalRoot: File
    private var wsDir: File? = null
    private var devDir: File? = null // null = storage-volumes level (root screen)
    private var tab = Tab.WORKSPACE
    private var awaitingAccess = false

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var pathView: TextView
    private lateinit var btnUp: TextView
    private lateinit var btnNewFolder: TextView
    private lateinit var btnImport: TextView

    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: EntryAdapter

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    // ------------------------------------------------------------- permission

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private val legacyPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) {
                refresh()
            } else if (tab == Tab.DEVICE) {
                Toast.makeText(this, R.string.files_access_denied, Toast.LENGTH_LONG).show()
            }
        }

    /**
     * Returns true when full storage access is already granted. Otherwise
     * starts the grant flow (system "All files access" screen on API 30+,
     * runtime permission dialog below) and returns false.
     */
    private fun ensureDeviceAccess(): Boolean {
        if (hasStorageAccess()) return true
        if (Build.VERSION.SDK_INT >= 30) {
            AlertDialog.Builder(this)
                .setTitle(R.string.files_access_title)
                .setMessage(R.string.files_access_msg)
                .setPositiveButton(R.string.files_access_grant) { _, _ ->
                    awaitingAccess = true
                    val appIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    try {
                        startActivity(appIntent)
                    } catch (_: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Exception) {
                            awaitingAccess = false
                            Toast.makeText(this, R.string.files_access_denied, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            legacyPermLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        return false
    }

    // ------------------------------------------------------------ launchers

    private val pickImportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val data = result.data!!
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                data.data?.let { if (uris.isEmpty()) uris.add(it) }
                if (uris.isNotEmpty()) importFromDevice(uris)
            }
        }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        wsRoot = File(filesDir, "workspace").apply { mkdirs() }
        wsDir = wsRoot
        internalRoot = Environment.getExternalStorageDirectory()

        adapter = EntryAdapter()
        listView = findViewById(R.id.files_list)
        emptyView = findViewById(R.id.files_empty_msg)
        pathView = findViewById(R.id.files_path)
        btnUp = findViewById(R.id.files_btn_up)
        btnNewFolder = findViewById(R.id.files_btn_new_folder)
        btnImport = findViewById(R.id.files_btn_import)

        findViewById<View>(R.id.files_back_btn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.files_tab_workspace).setOnClickListener { selectTab(Tab.WORKSPACE) }
        findViewById<TextView>(R.id.files_tab_device).setOnClickListener { selectTab(Tab.DEVICE) }
        btnUp.setOnClickListener { navigateUp() }
        btnNewFolder.setOnClickListener { newFolderDialog() }
        btnImport.setOnClickListener { pickImport() }
        findViewById<View>(R.id.files_btn_refresh).setOnClickListener { refresh() }

        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@OnItemClickListener
            if (e.isDir) openDir(e) else fileActions(e)
        }
        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@OnItemLongClickListener false
            if (!e.isDir) return@OnItemLongClickListener false
            // No per-item actions on the storage-volumes screen (level 0).
            if (tab == Tab.DEVICE && devDir == null) return@OnItemLongClickListener false
            dirActions(e)
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigateUp()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        selectTab(Tab.WORKSPACE)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingAccess) {
            awaitingAccess = false
            if (hasStorageAccess() && tab == Tab.DEVICE) refresh()
        }
    }

    // ------------------------------------------------------------- rendering

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        return if (theme.resolveAttribute(attr, tv, true)) tv.data else 0
    }

    private fun selectTab(t: Tab) {
        tab = t
        val tabW: TextView = findViewById(R.id.files_tab_workspace)
        val tabD: TextView = findViewById(R.id.files_tab_device)
        val activeBg = attrColor(R.attr.extraKeysBg)
        val bg = attrColor(R.attr.terminalBg)
        val text = attrColor(R.attr.terminalText)
        val on = { tv: TextView, selected: Boolean ->
            tv.setBackgroundColor(if (selected) activeBg else bg)
            tv.setTextColor(text)
            tv.alpha = if (selected) 1f else 0.55f
        }
        on(tabW, t == Tab.WORKSPACE)
        on(tabD, t == Tab.DEVICE)

        btnNewFolder.visibility = View.VISIBLE
        btnImport.visibility = if (t == Tab.WORKSPACE) View.VISIBLE else View.GONE

        if (t == Tab.DEVICE) ensureDeviceAccess()
        refresh()
    }

    private fun refresh() {
        if (tab == Tab.WORKSPACE) renderWorkspace() else renderDevice()
    }

    private fun show(list: List<Entry>, path: String) {
        entries.clear()
        entries.addAll(
            list.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        )
        adapter.notifyDataSetChanged()
        pathView.text = path
        val empty = entries.isEmpty()
        listView.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            emptyView.text = when {
                tab == Tab.WORKSPACE -> getString(R.string.files_empty_workspace)
                !hasStorageAccess() -> getString(R.string.files_access_needed)
                else -> getString(R.string.files_empty_device)
            }
        }
    }

    private fun renderWorkspace() {
        val dir = wsDir ?: wsRoot
        val list = dir.listFiles()?.map {
            Entry(it.name, it.isDirectory, it.length(), file = it)
        } ?: emptyList()
        val rel = dir.absolutePath.removePrefix(wsRoot.absolutePath)
        show(list, "/workspace$rel")
    }

    /** All readable storage roots: internal storage first, then SD cards etc. */
    private fun volumeRoots(): List<File> {
        val list = mutableListOf(internalRoot)
        try {
            File("/storage").listFiles()?.forEach { f ->
                if (f.name == "self" || f.name == "emulated") return@forEach
                if (f.isDirectory && f.canRead() && f.path != internalRoot.path) list.add(f)
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun volumeLabel(f: File): String = when {
        f.path == internalRoot.path -> getString(R.string.files_internal_storage)
        f.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) ->
            getString(R.string.files_sd_card, f.name)
        else -> f.name
    }

    private fun isVolumeRoot(f: File) = volumeRoots().any { it.path == f.path }

    private fun renderDevice() {
        if (!hasStorageAccess()) {
            show(emptyList(), getString(R.string.files_device))
            return
        }
        val dir = devDir
        Thread {
            try {
                if (dir == null) {
                    // Root screen: the list of storage volumes.
                    val list = volumeRoots().map {
                        Entry(volumeLabel(it), true, it.length(), file = it)
                    }
                    runOnUiThread { show(list, getString(R.string.files_storage)) }
                } else {
                    val kids = dir.listFiles()?.map {
                        Entry(it.name, it.isDirectory, it.length(), file = it)
                    } ?: emptyList()
                    val path = dir.absolutePath
                    runOnUiThread { show(kids, path) }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    show(emptyList(), getString(R.string.files_device))
                    Toast.makeText(this, e.message ?: "error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ----------------------------------------------------------- navigation

    private fun openDir(e: Entry) {
        val f = e.file ?: return
        if (tab == Tab.WORKSPACE) wsDir = f else devDir = f
        refresh()
    }

    /** Returns true when the current list moved up one level. */
    private fun navigateUp(): Boolean {
        if (tab == Tab.WORKSPACE) {
            val cur = wsDir ?: wsRoot
            if (cur == wsRoot) return false
            wsDir = cur.parentFile ?: wsRoot
            refresh()
            return true
        }
        val cur = devDir ?: return false
        devDir = if (isVolumeRoot(cur)) {
            null // back to the storage-volumes screen
        } else {
            cur.parentFile?.takeIf { it.path != "/storage" && it.path != "/" }
        }
        refresh()
        return true
    }

    // ------------------------------------------------------------- actions

    private fun fileActions(e: Entry) {
        if (tab == Tab.WORKSPACE) {
            val items = arrayOf(
                getString(R.string.files_export_to_device),
                getString(R.string.rename),
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { exportToDevice(e) },
                { renameEntry(e) },
                { confirmDelete(e) }
            )
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(items) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            val items = arrayOf(
                getString(R.string.files_import_action),
                getString(R.string.rename),
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { importDeviceEntry(e) },
                { renameEntry(e) },
                { confirmDelete(e) }
            )
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(items) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun dirActions(e: Entry) {
        if (tab == Tab.WORKSPACE) {
            val items = arrayOf(
                getString(R.string.files_export_to_device),
                getString(R.string.rename),
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { exportToDevice(e) },
                { renameEntry(e) },
                { confirmDelete(e) }
            )
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(items) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            val items = arrayOf(
                getString(R.string.files_import_action),
                getString(R.string.rename),
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { importDeviceEntry(e) },
                { renameEntry(e) },
                { confirmDelete(e) }
            )
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(items) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun newFolderDialog() {
        val target: File? = if (tab == Tab.WORKSPACE) wsDir ?: wsRoot else devDir
        if (target == null || (tab == Tab.DEVICE && !ensureDeviceAccess())) {
            Toast.makeText(this, R.string.files_cannot_create_here, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply { hint = getString(R.string.files_folder_name_hint) }
        AlertDialog.Builder(this).setTitle(R.string.files_new_folder).setView(input)
            .setPositiveButton(R.string.files_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val f = File(target, name)
                    if (f.exists()) {
                        Toast.makeText(this, R.string.files_new_folder_exists, Toast.LENGTH_SHORT).show()
                    } else {
                        f.mkdirs()
                        refresh()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameEntry(e: Entry) {
        val f = e.file ?: return
        val input = EditText(this).apply { setText(e.name); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.files_rename_title).setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == e.name) return@setPositiveButton
                val ok = f.renameTo(File(f.parentFile, newName))
                Toast.makeText(
                    this,
                    if (ok) R.string.files_renamed else R.string.files_op_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(e: Entry) {
        val f = e.file ?: return
        AlertDialog.Builder(this).setTitle(R.string.files_delete_title)
            .setMessage(getString(R.string.files_delete_msg, e.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = f.deleteRecursively()
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (ok) R.string.files_delete_done else R.string.files_op_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------- device -> workspace

    private fun importDeviceEntry(e: Entry) {
        val src = e.file ?: return
        val targetBase = wsDir ?: wsRoot
        Thread {
            try {
                val name = copyTo(src, targetBase).name
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.files_import_done, name), Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_import_failed, ex.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pickImportLauncher.launch(intent)
    }

    private fun importFromDevice(uris: List<Uri>) {
        val targetBase = wsDir ?: wsRoot
        Thread {
            val done = mutableListOf<String>()
            var err: String? = null
            for (uri in uris) {
                try {
                    val name = displayName(uri)
                    val target = uniqueFile(targetBase, name)
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException(name)
                    input.use { src ->
                        target.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    done.add(target.name)
                } catch (e: Exception) {
                    err = e.message ?: "?"
                    break
                }
            }
            runOnUiThread {
                if (err == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.files_import_done, done.joinToString(", ")),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.files_import_failed, err),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (tab == Tab.WORKSPACE) refresh()
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) c.getString(i)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun uniqueFile(dir: File, name: String): File {
        val direct = File(dir, name)
        if (!direct.exists()) return direct
        val base = if (name.contains('.')) name.substringBeforeLast('.') else name
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i++
        return File(dir, "$base ($i)$ext")
    }

    // ------------------------------------------------------- workspace -> device

    private fun exportToDevice(e: Entry) {
        val src = e.file ?: return
        if (!ensureDeviceAccess()) return
        val target = devDir ?: internalRoot
        Thread {
            try {
                val dst = copyTo(src, target)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_export_done, dst.path),
                        Toast.LENGTH_SHORT
                    ).show()
                    if (tab == Tab.DEVICE) refresh()
                }
            } catch (ex: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_export_failed, ex.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /** Recursive copy; returns the final destination (renamed on conflict). */
    private fun copyTo(src: File, into: File): File {
        if (src.isDirectory) {
            val dir = uniqueFile(into, src.name)
            dir.mkdirs()
            src.listFiles()?.forEach { copyTo(it, dir) }
            return dir
        }
        val target = uniqueFile(into, src.name)
        src.inputStream().use { ins -> target.outputStream().use { dst -> ins.copyTo(dst) } }
        return target
    }

    // -------------------------------------------------------------- adapter

    private inner class EntryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_file_row, parent, false)
            val e = entries[position]
            view.findViewById<TextView>(R.id.row_icon).text = if (e.isDir) "📁" else "📄"
            view.findViewById<TextView>(R.id.row_name).text = e.name
            view.findViewById<TextView>(R.id.row_sub).text =
                if (e.isDir) getString(R.string.files_folder_label)
                else getString(R.string.files_file_label, humanSize(e.size))
            return view
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
