package com.redtermapp.util

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream

/**
 * In-app preview dialog for workspace files.
 *
 * Three renderers:
 *  - text: UTF-8 sniffed by NUL bytes in the first 8 KB (extension
 *    independent, so Makefile/README/lockfiles all work), first 300 KB shown,
 *    monospaced and selectable;
 *  - raster images (png/jpg/gif/webp/bmp): bounds-decoded and downsampled to
 *    <= 2048 px so a huge photo cannot OOM the dialog;
 *  - SVG: rendered by an offline WebView from a base64 data: URL (no file
 *    access, no JS) - the only way to get crisp vector output from a dialog.
 *
 * Anything else (or a file whose first bytes look binary) gets a size/type
 * card. An optional [insertPath] callback adds a button so the picker flow
 * can still type the path after previewing.
 */
object FilePreview {

    private const val TEXT_MAX = 300 * 1024
    private const val TEXT_SNIFF = 8 * 1024
    private const val SVG_MAX = 4 * 1024 * 1024
    private const val IMAGE_MAX_DIM = 2048

    private val RASTER_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    fun show(context: Activity, file: File, insertPath: (() -> Unit)? = null) {
        if (!file.isFile) return
        val builder = AlertDialog.Builder(context)
            .setTitle("${file.name} · ${humanSize(file.length())}")
            .setView(body(context, file))
            .setNegativeButton(android.R.string.cancel, null)
        if (insertPath != null) {
            builder.setPositiveButton("Insert path") { _, _ -> insertPath() }
        }
        builder.show()
    }

    private fun body(context: Activity, file: File): View = try {
        when {
            file.name.endsWith(".svg", ignoreCase = true) -> svgBody(context, file)
            extOf(file) in RASTER_EXT -> imageBody(context, file)
            else -> textBody(context, file)
        }
    } catch (e: Exception) {
        card(context, "Cannot preview: ${e.message ?: "error"}")
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    private fun textBody(context: Activity, file: File): View {
        val cap = minOf(file.length(), (TEXT_MAX + 1L)).toInt()
        val buf = ByteArray(cap)
        var off = 0
        FileInputStream(file).use { input ->
            while (off < cap) {
                val r = input.read(buf, off, cap - off)
                if (r < 0) break
                off += r
            }
        }
        val sniffed = buf.copyOf(minOf(off, TEXT_SNIFF))
        if (sniffed.any { it == 0.toByte() }) {
            return card(context, "Binary file - no text preview.\n${humanSize(file.length())}")
        }
        val truncated = off > TEXT_MAX
        val shown = String(buf, 0, if (truncated) TEXT_MAX else off, Charsets.UTF_8)
        // NB: named `content`, not `text` - a local `text` would shadow the
        // TextView's `text` property inside apply {} and fail to compile.
        val content = if (truncated) {
            "// First 300 KB of ${humanSize(file.length())} — truncated.\n\n$shown"
        } else shown
        val tv = TextView(context).apply {
            text = content
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        return ScrollView(context).apply { addView(tv) }
    }

    // ------------------------------------------------------------------
    // Raster images
    // ------------------------------------------------------------------

    private fun imageBody(context: Activity, file: File): View {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > IMAGE_MAX_DIM ||
            bounds.outHeight / sample > IMAGE_MAX_DIM
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return card(context, "Cannot decode image.")
        val iv = ImageView(context).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(16, 16, 16, 16)
        }
        return ScrollView(context).apply {
            isFillViewport = true
            addView(iv, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    // ------------------------------------------------------------------
    // SVG
    // ------------------------------------------------------------------

    private fun svgBody(context: Activity, file: File): View {
        val size = file.length()
        if (size > SVG_MAX) {
            return card(context, "SVG too large to preview (${humanSize(size)}).")
        }
        val bytes = file.readBytes()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(0x00000000)
            loadDataWithBaseURL(
                null,
                "<html><body style=\"margin:0;background:transparent\">" +
                    "<img src=\"data:image/svg+xml;base64,$b64\" style=\"max-width:100%\">" +
                    "</body></html>",
                "text/html",
                "utf-8",
                null
            )
        }
        val h = (context.resources.displayMetrics.heightPixels * 6) / 10
        return LinearLayout(context).apply {
            addView(wv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h
            ))
        }
    }

    // ------------------------------------------------------------------
    // Fallback
    // ------------------------------------------------------------------

    private fun card(context: Activity, message: String): View =
        TextView(context).apply {
            text = message
            textSize = 14f
            setPadding(48, 40, 48, 40)
        }

    private fun extOf(file: File): String {
        val n = file.name
        val dot = n.lastIndexOf('.')
        return if (dot in 0 until n.length - 1) n.substring(dot + 1).lowercase() else ""
    }

    private fun humanSize(n: Long): String = when {
        n < 1024L -> "$n B"
        n < 1024L * 1024L -> "%.1f KB".format(n / 1024.0)
        else -> "%.1f MB".format(n / 1024.0 / 1024.0)
    }
}
