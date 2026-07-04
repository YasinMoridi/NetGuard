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

import android.annotation.TargetApi
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import kotlin.system.exitProcess

class ApplicationEx : Application() {
    private var mPrevHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Create version=${Util.getSelfVersionName(this)}/${Util.getSelfVersionCode(this)}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels()
        }

        mPrevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            if (Util.ownFault(this@ApplicationEx, ex) && Util.isPlayStoreInstall(this@ApplicationEx)) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                mPrevHandler?.uncaughtException(thread, ex)
            } else {
                Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                exitProcess(1)
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT >= 35 && false) { // 35 is VANILLA_ICE_CREAM
                    val content = activity.findViewById<android.view.View>(android.R.id.content)
                    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                        val bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars() or
                                    WindowInsetsCompat.Type.displayCutout() or
                                    WindowInsetsCompat.Type.ime()
                        )

                        val tv = TypedValue()
                        activity.theme.resolveAttribute(R.attr.colorPrimaryDark, tv, true)

                        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
                        val dark = prefs.getBoolean("dark_theme", false)

                        activity.window.decorView.setBackgroundColor(tv.data)
                        content.setBackgroundColor(if (dark) Color.parseColor("#ff121212") else Color.WHITE)

                        val actionBarHeight = Util.dips2pixels(56, activity)
                        val decor = activity.window.decorView
                        WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightStatusBars = false
                        WindowCompat.getInsetsController(activity.window, decor).isAppearanceLightNavigationBars = !dark
                        v.setPadding(bars.left, bars.top + actionBarHeight, bars.right, bars.bottom)

                        insets
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val foreground = NotificationChannel("foreground", getString(R.string.channel_foreground), NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT)
        }
        nm.createNotificationChannel(foreground)

        val notify = NotificationChannel("notify", getString(R.string.channel_notify), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(notify)

        val access = NotificationChannel("access", getString(R.string.channel_access), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(access)

        val malware = NotificationChannel("malware", getString(R.string.setting_malware), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, Notification.AUDIO_ATTRIBUTES_DEFAULT)
            setBypassDnd(true)
        }
        nm.createNotificationChannel(malware)
    }

    companion object {
        private const val TAG = "NetGuard.App"
    }
}
