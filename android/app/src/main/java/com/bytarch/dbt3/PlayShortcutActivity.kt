package com.bytarch.dbt3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.bytarch.dbt3.data.GamePaths
import com.bytarch.dbt3.engine.EngineBridge
import com.bytarch.dbt3.engine.GameActivity

/**
 * The target of the "Play" shortcuts (the icon on the home screen, and the one in the app icon's long-press menu):
 * it shows nothing and starts the game at once. If the game is not installed yet, the launcher opens instead, so
 * that the person sees what is missing.
 */
class PlayShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paths = GamePaths(this)
        val ready = paths.installedMarker.exists() && EngineBridge.isAvailable(this)
        if (ready) paths.setOnlineEnv(false) // the shortcut plays the game as it is, never the online screen
        val target = if (ready) GameActivity::class.java else MainActivity::class.java
        startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
