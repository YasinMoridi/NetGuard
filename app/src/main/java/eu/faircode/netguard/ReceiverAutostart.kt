package eu.faircode.netguard

/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2026 by Marcel Bokhorst (M66B)
*/

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

open class ReceiverAutostart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Received $intent")
        Util.logExtras(intent)

        val action = intent?.action
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            try {
                // Upgrade settings
                upgrade(true, context)

                // Start service
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                if (prefs.getBoolean("enabled", false)) {
                    ServiceSinkhole.start("receiver", context)
                } else if (prefs.getBoolean("show_stats", false)) {
                    ServiceSinkhole.run("receiver", context)
                }

                if (Util.isInteractive(context)) {
                    ServiceSinkhole.reloadStats("receiver", context)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }
    }

    companion object {
        private const val TAG = "NetGuard.Receiver"

        @JvmStatic
        fun upgrade(initialized: Boolean, context: Context) {
            synchronized(context.applicationContext) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val oldVersion = prefs.getInt("version", -1)
                val newVersion = Util.getSelfVersionCode(context)
                if (oldVersion == newVersion) {
                    return
                }
                Log.i(TAG, "Upgrading from version $oldVersion to $newVersion")

                val editor = prefs.edit()

                if (initialized) {
                    if (oldVersion < 38) {
                        Log.i(TAG, "Converting screen wifi/mobile")
                        editor.putBoolean("screen_wifi", prefs.getBoolean("unused", false))
                        editor.putBoolean("screen_other", prefs.getBoolean("unused", false))
                        editor.remove("unused")

                        val unused = context.getSharedPreferences("unused", Context.MODE_PRIVATE)
                        val screenWifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE)
                        val screenOther = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE)

                        val pUnused = unused.all
                        val editScreenWifi = screenWifi.edit()
                        val editScreenOther = screenOther.edit()
                        for (key in pUnused.keys) {
                            val value = pUnused[key] as? Boolean ?: false
                            editScreenWifi.putBoolean(key, value)
                            editScreenOther.putBoolean(key, value)
                        }
                        editScreenWifi.apply()
                        editScreenOther.apply()
                    } else if (oldVersion <= 2017032112) {
                        editor.remove("ip6")
                    }
                } else {
                    Log.i(TAG, "Initializing sdk=${Build.VERSION.SDK_INT}")
                    editor.putBoolean("filter_udp", true)
                    editor.putBoolean("whitelist_wifi", false)
                    editor.putBoolean("whitelist_other", false)
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) {
                        editor.putBoolean("filter", true) // Optional
                    }
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    editor.putBoolean("filter", true) // Mandatory
                }

                if (!Util.canFilter(context)) {
                    editor.putBoolean("log_app", false)
                    editor.putBoolean("filter", false)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    editor.remove("show_top")
                    if ("data" == prefs.getString("sort", "name")) {
                        editor.remove("sort")
                    }
                }

                if (Util.isPlayStoreInstall(context)) {
                    editor.remove("update_check")
                    editor.remove("use_hosts")
                    editor.remove("hosts_url")
                }

                if (!Util.isDebuggable(context)) {
                    editor.remove("loglevel")
                }

                editor.putInt("version", newVersion)
                editor.apply()
            }
        }
    }
}
