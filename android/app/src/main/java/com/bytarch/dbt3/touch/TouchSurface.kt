package com.bytarch.dbt3.touch

import android.content.Context
import android.view.View

/** What the in-game overlay and the launcher's editor need of an on-screen controller, whichever layout it is. */
interface TouchListener {
    fun onPad(buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int) {}
    fun onMenu() {}
    fun onOptions() {}
    fun onConfigChanged(config: TouchConfig) {}
    fun onEditDone() {}
}

interface TouchSurface {
    var listener: TouchListener?
    var config: TouchConfig
    var editing: Boolean
    var suppressed: Boolean
    var hiddenForPad: Boolean
}

/** The view of the layout the config asks for: the game-specific one (default) or the classic generic pad. */
fun createTouchSurface(context: Context, config: TouchConfig, editing: Boolean = false): TouchSurface =
    if (config.layout == TouchLayout.CLASSIC) ClassicPadView(context, config, editing) else TouchPadView(context, config, editing)

val TouchSurface.view: View get() = this as View
