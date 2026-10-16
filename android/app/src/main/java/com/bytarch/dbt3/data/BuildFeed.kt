package com.bytarch.dbt3.data

import java.io.IOException
import org.json.JSONObject

/**
 * The build feed on the project's own server: one small JSON next to the ISO, read at every launch. A new build can be
 * announced — and its APK pointed at — by replacing that one file, with no new app version.
 *
 *   {"version":"0.6.0","versionCode":600,"apkUrl":"https://…/app-release.apk","size":12345678,"notes":"…"}
 */
object BuildFeed {
    const val URL = "https://gcode.bytarch.com/version.json"

    data class Feed(val version: String, val versionCode: Int, val notes: String, val apkUrl: String, val size: Long) {
        fun newerThan(code: Int): Boolean = versionCode > code
    }

    suspend fun fetch(): Feed {
        val o = JSONObject(Net.getText(URL))
        val apk = o.optString("apkUrl")
        if (!apk.startsWith("https://")) throw IOException("version.json has no https apkUrl")
        return Feed(
            o.optString("version"),
            o.optInt("versionCode", -1),
            o.optString("notes").take(8000),
            apk,
            o.optLong("size", -1),
        )
    }
}
