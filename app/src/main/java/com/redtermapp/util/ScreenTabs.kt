package com.redtermapp.util

import android.content.Intent
import android.util.TypedValue
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.redtermapp.R
import com.redtermapp.ui.BrowserActivity
import com.redtermapp.ui.FilesActivity
import com.redtermapp.ui.TerminalActivity

/**
 * The shared top tab strip (Terminal / Browser / Files).
 *
 * Every screen includes `R.layout.tab_strip` as its FIRST child and calls
 * [attach] from onCreate with its own cell id. Switching uses
 * `FLAG_ACTIVITY_REORDER_TO_FRONT`, so an already-open screen is brought
 * forward instead of recreated: the terminal session, the loaded page and
 * the opened folder all survive. The strip is a docked42 dp row inside the
 * layout - it covers nothing and pushes content down by its own height only.
 */
object ScreenTabs {

    fun attach(activity: AppCompatActivity, activeId: Int) {
        // colorPrimary lives in the Material library's R, not the app's.
        val activeColor = resolveColor(activity, com.google.android.material.R.attr.colorPrimary)
        val idleColor = resolveColor(activity, R.attr.terminalText)
        val targets = mapOf<Int, Class<*>>(
            R.id.tab_terminal to TerminalActivity::class.java,
            R.id.tab_browser to BrowserActivity::class.java,
            R.id.tab_files to FilesActivity::class.java
        )
        for ((id, cls) in targets) {
            val btn = activity.findViewById<ImageView>(id) ?: continue
            val active = id == activeId
            btn.alpha = if (active) 1f else 0.45f
            btn.setColorFilter(if (active) activeColor else idleColor)
            if (active) {
                btn.isClickable = false
                btn.setOnClickListener(null)
            } else {
                btn.setOnClickListener {
                    activity.startActivity(
                        Intent(activity, cls).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }
    }

    private fun resolveColor(activity: AppCompatActivity, attr: Int): Int {
        val tv = TypedValue()
        return if (activity.theme.resolveAttribute(attr, tv, true)) {
            tv.data
        } else {
            0xFFCDD6F4.toInt()
        }
    }
}
