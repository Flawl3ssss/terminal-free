package com.redtermapp.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import com.redtermapp.R
import java.io.File

/**
 * Two-way file bridge between the device and the container's /workspace.
 *
 * Workspace tab: browses filesDir/workspace (host-backed, bind-mounted to
 * /workspace inside proot). Device tab and all import/export operations use
 * the Storage Access Framework (ACTION_OPEN_DOCUMENT_TREE / OPEN_DOCUMENT /
 * CREATE_DOCUMENT) - the app holds NO storage permissions and the container
 * never sees any device filesystem path.
 */
class FilesActivity : AppCompatActivity() {

    private enum class Tab { WORKSPACE, DEVICE }

    private data class Entry(
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val file: File? = null,
        val doc: DocumentFile? = null
    )

    private lateinit var wsRoot: File
    private var wsDir: File? = null
    private var tab = Tab.WORKSPACE
    private var treeUri: Uri? = null
    private var deviceDir: DocumentFile? = null // null = tree root

    private var pendingExportFile: File? = null
    private var pendingExportDir: File? = null
    private var pendingTreeAction = 0 // 0 = choose device folder, 1 = export dir

    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var pathView: TextView
    private lateinit var btnUp: TextView
    private lateinit var btnNewFolder: TextView
    private lateinit var btnImport: TextView
    private lateinit var btnChooseFolder: TextView

    private val entries = mutableListOf<Entry>()
    private lateinit var adapter: EntryAdapter

    private fun prefs() = getSharedPreferences("settings", MODE_PRIVATE)

    // ------------------------------------------------------------ launchers

    private val pickTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                }
                prefs().edit { putString("device_tree_uri", uri.toString()) }
                treeUri = uri
                deviceDir = null
                if (pendingTreeAction == 1) {
                    val dir = pendingExportDir
                    pendingTreeAction = 0
                    pendingExportDir = null
                    dir?.let { doExportDir(it) }
                } else {
                    selectTab(Tab.DEVICE)
                    refresh()
                }
            } else {
                pendingTreeAction = 0
                pendingExportDir = null
            }
        }

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

    private val createDocLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val src = pendingExportFile
            pendingExportFile = null
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null && src != null) {
                copyOut(src, uri)
            }
        }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)

        wsRoot = File(filesDir, "workspace").apply { mkdirs() }
        wsDir = wsRoot
        treeUri = prefs().getString("device_tree_uri", null)?.let(Uri::parse)

        adapter = EntryAdapter()
        listView = findViewById(R.id.files_list)
        emptyView = findViewById(R.id.files_empty_msg)
        pathView = findViewById(R.id.files_path)
        btnUp = findViewById(R.id.files_btn_up)
        btnNewFolder = findViewById(R.id.files_btn_new_folder)
        btnImport = findViewById(R.id.files_btn_import)
        btnChooseFolder = findViewById(R.id.files_btn_choose_folder)

        findViewById<View>(R.id.files_back_btn).setOnClickListener { finish() }
        findViewById<TextView>(R.id.files_tab_workspace).setOnClickListener { selectTab(Tab.WORKSPACE) }
        findViewById<TextView>(R.id.files_tab_device).setOnClickListener { selectTab(Tab.DEVICE) }
        btnUp.setOnClickListener { navigateUp() }
        btnNewFolder.setOnClickListener { newFolderDialog() }
        btnImport.setOnClickListener { pickImport() }
        btnChooseFolder.setOnClickListener { pickDeviceFolder() }
        findViewById<View>(R.id.files_btn_refresh).setOnClickListener { refresh() }

        listView.adapter = adapter
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@OnItemClickListener
            if (e.isDir) openDir(e) else fileActions(e)
        }
        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            val e = entries.getOrNull(position) ?: return@OnItemLongClickListener false
            if (e.isDir) { dirActions(e); true } else false
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

        val isWs = t == Tab.WORKSPACE
        btnNewFolder.visibility = if (isWs) View.VISIBLE else View.GONE
        btnImport.visibility = if (isWs) View.VISIBLE else View.GONE
        btnChooseFolder.visibility = if (isWs) View.GONE else View.VISIBLE
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
                treeUri == null -> getString(R.string.files_no_device_folder)
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

    private fun renderDevice() {
        val uri = treeUri
        if (uri == null) {
            show(emptyList(), getString(R.string.files_device))
            return
        }
        Thread {
            try {
                val root = DocumentFile.fromTreeUri(this, uri)
                    ?: throw IllegalStateException("Cannot open the device folder")
                val dir = deviceDir ?: root
                val kids = dir.listFiles().map { d ->
                    Entry(d.name ?: "?", d.isDirectory, d.length(), doc = d)
                }
                val title = (if (deviceDir == null) root.name else deviceDir?.name) ?: ""
                runOnUiThread { show(kids, title) }
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
        if (tab == Tab.WORKSPACE) {
            wsDir = e.file ?: return
        } else {
            deviceDir = e.doc ?: return
        }
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
        val cur = deviceDir ?: return false
        return try {
            val uri = treeUri ?: return false
            val root = DocumentFile.fromTreeUri(this, uri)
            val parent = cur.parentFile
            deviceDir = if (parent == null || root == null || parent.uri == root.uri) null else parent
            refresh()
            true
        } catch (_: Exception) {
            false
        }
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
                { exportFile(e) },
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
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { e.doc?.let { doc -> importDeviceDoc(doc) } },
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
                { exportDir(e) },
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
                getString(R.string.delete)
            )
            val actions = arrayOf<() -> Unit>(
                { e.doc?.let { doc -> importDeviceDoc(doc) } },
                { confirmDelete(e) }
            )
            AlertDialog.Builder(this).setTitle(e.name)
                .setItems(items) { _, which -> actions[which]() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun newFolderDialog() {
        val input = EditText(this).apply { hint = getString(R.string.files_folder_name_hint) }
        AlertDialog.Builder(this).setTitle(R.string.files_new_folder).setView(input)
            .setPositiveButton(R.string.files_create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val f = File(wsDir ?: wsRoot, name)
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
        val input = EditText(this).apply { setText(e.name); selectAll() }
        AlertDialog.Builder(this).setTitle(R.string.files_rename_title).setView(input)
            .setPositiveButton(R.string.rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == e.name) return@setPositiveButton
                val ok = if (tab == Tab.WORKSPACE) {
                    val f = e.file ?: return@setPositiveButton
                    f.renameTo(File(f.parentFile, newName))
                } else {
                    e.doc?.renameTo(newName) == true
                }
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
        AlertDialog.Builder(this).setTitle(R.string.files_delete_title)
            .setMessage(getString(R.string.files_delete_msg, e.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                Thread {
                    val ok = if (tab == Tab.WORKSPACE) {
                        e.file?.deleteRecursively() == true
                    } else {
                        e.doc?.delete() == true
                    }
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

    private fun pickDeviceFolder() {
        pendingTreeAction = 0
        pickTreeLauncher.launch(null)
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

    private fun importDeviceDoc(doc: DocumentFile) {
        val targetBase = wsDir ?: wsRoot
        Thread {
            try {
                val name = importDocInto(doc, targetBase)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.files_import_done, name), Toast.LENGTH_SHORT).show()
                    if (tab == Tab.WORKSPACE) refresh()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_import_failed, e.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun importDocInto(doc: DocumentFile, into: File): String {
        val name = doc.name ?: "import"
        if (doc.isDirectory) {
            val dir = uniqueFile(into, name)
            dir.mkdirs()
            doc.listFiles().forEach { importDocInto(it, dir) }
            return dir.name
        }
        val target = uniqueFile(into, name)
        val input = contentResolver.openInputStream(doc.uri)
            ?: throw IllegalStateException(name)
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        return target.name
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

    private fun exportFile(e: Entry) {
        val f = e.file ?: return
        pendingExportFile = f
        val ext = f.extension.lowercase()
        val mime = if (ext.isNotEmpty()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) else null
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime ?: "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, f.name)
        }
        createDocLauncher.launch(intent)
    }

    private fun copyOut(src: File, uri: Uri) {
        Thread {
            try {
                val out = contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException(src.name)
                out.use { dst -> src.inputStream().use { it.copyTo(dst) } }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.files_export_done, src.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_export_failed, e.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun exportDir(e: Entry) {
        val f = e.file ?: return
        if (treeUri == null) {
            pendingTreeAction = 1
            pendingExportDir = f
            pickTreeLauncher.launch(null)
        } else {
            doExportDir(f)
        }
    }

    private fun doExportDir(src: File) {
        val uri = treeUri ?: return
        Thread {
            try {
                val root = DocumentFile.fromTreeUri(this, uri)
                    ?: throw IllegalStateException("no folder")
                val target = root.findFile(src.name) ?: root.createDirectory(src.name)
                    ?: throw IllegalStateException("cannot create folder")
                copyIntoDoc(src, target)
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.files_export_done, src.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.files_export_failed, e.message ?: "?"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun copyIntoDoc(src: File, dst: DocumentFile) {
        if (src.isDirectory) {
            val sub = dst.findFile(src.name) ?: dst.createDirectory(src.name) ?: return
            src.listFiles()?.forEach { copyIntoDoc(it, sub) }
        } else {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(src.extension.lowercase())
                ?: "application/octet-stream"
            val out = dst.createFile(mime, src.name) ?: return
            contentResolver.openOutputStream(out.uri)?.use { os ->
                src.inputStream().use { it.copyTo(os) }
            }
        }
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
