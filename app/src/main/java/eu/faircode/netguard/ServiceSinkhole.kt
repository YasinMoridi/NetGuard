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

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.Pair
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.HashMap
import java.util.HashSet
import java.util.Locale
import java.util.Objects
import java.util.TreeMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.read
import kotlin.concurrent.write

@Suppress("DEPRECATION")
class ServiceSinkhole : VpnService(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var registeredUser = false
    private var registeredIdleState = false
    private var registeredApState = false
    private var registeredConnectivityChanged = false
    private var registeredPackageChanged = false

    private var phone_state = false
    private var networkCallback: Any? = null

    private var registeredInteractiveState = false
    private var callStateListener: PhoneStateListener? = null

    private var state = State.none
    private var user_foreground = true
    private var last_connected = false
    private var last_metered = true
    private var last_interactive = false

    private var last_allowed = -1
    private var last_blocked = -1
    private var last_hosts = -1

    private var tunnelThread: Thread? = null
    private var last_builder: Builder? = null
    private var vpn: ParcelFileDescriptor? = null
    private var temporarilyStopped = false

    private var last_hosts_modified: Long = 0
    private var last_malware_modified: Long = 0
    private val mapHostsBlocked = HashMap<String, Boolean>()
    private val mapMalware = HashMap<String, Boolean>()
    private val mapUidAllowed = HashMap<Int, Boolean>()
    private val mapUidKnown = HashMap<Int, Int>()
    private val mapUidIPFilters = HashMap<IPKey, MutableMap<InetAddress, IPRule>>()
    private val mapForward = HashMap<Int, Forward>()
    private val mapNotify = HashMap<Int, Boolean>()
    private val lock = ReentrantReadWriteLock(true)

    private lateinit var commandLooper: Looper
    private lateinit var logLooper: Looper
    private lateinit var statsLooper: Looper
    private lateinit var commandHandler: CommandHandler
    private lateinit var logHandler: LogHandler
    private lateinit var statsHandler: StatsHandler

    private var executor: ExecutorService = Executors.newCachedThreadPool()

    private external fun jni_init(sdk: Int): Long
    private external fun jni_start(context: Long, loglevel: Int)
    private external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jni_stop(context: Long)
    private external fun jni_clear(context: Long)
    private external fun jni_get_mtu(): Int
    private external fun jni_get_stats(context: Long): IntArray
    private external fun jni_socks5(addr: String, port: Int, username: String, password: String)
    private external fun jni_done(context: Long)

    private val interactiveStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)

            executor.submit {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val i = Intent(ACTION_SCREEN_OFF_DELAYED)
                i.setPackage(context.packageName)
                val pi = PendingIntentCompat.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT)
                am.cancel(pi)

                try {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    var delay = 0
                    try {
                        delay = prefs.getString("screen_delay", "0")?.toInt() ?: 0
                    } catch (ignored: NumberFormatException) {
                    }
                    val interactive = Intent.ACTION_SCREEN_ON == intent.action

                    if (interactive || delay == 0) {
                        last_interactive = interactive
                        reload("interactive state changed", this@ServiceSinkhole, true)
                    } else {
                        if (ACTION_SCREEN_OFF_DELAYED == intent.action) {
                            last_interactive = interactive
                            reload("interactive state changed", this@ServiceSinkhole, true)
                        } else {
                            val triggerAtMillis = Date().time + delay * 60 * 1000L
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                                am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                            } else {
                                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                            }
                        }
                    }

                    statsHandler.sendEmptyMessage(
                        if (Util.isInteractive(this@ServiceSinkhole)) MSG_STATS_START else MSG_STATS_STOP
                    )
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    val retryAtMillis = Date().time + 15 * 1000L
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        am.set(AlarmManager.RTC_WAKEUP, retryAtMillis, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, retryAtMillis, pi)
                    }
                }
            }
        }
    }

    private val userReceiver = object : BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)

            user_foreground = Intent.ACTION_USER_FOREGROUND == intent.action
            Log.i(TAG, "User foreground=$user_foreground user=${Process.myUid() / 100000}")

            if (user_foreground) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("enabled", false)) {
                    try {
                        Thread.sleep(3000)
                    } catch (ignored: InterruptedException) {
                    }
                    start("foreground", this@ServiceSinkhole)
                }
            } else {
                stop("background", this@ServiceSinkhole, true)
            }
        }
    }

    private val idleStateReceiver = object : BroadcastReceiver() {
        @TargetApi(Build.VERSION_CODES.M)
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            Log.i(TAG, "device idle=${pm.isDeviceIdleMode}")

            if (!pm.isDeviceIdleMode) {
                reload("idle state changed", this@ServiceSinkhole, false)
            }
        }
    }

    private val apStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            reload("AP state changed", this@ServiceSinkhole, false)
        }
    }

    private val connectivityChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                val networkType = intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_DUMMY)
                if (networkType == ConnectivityManager.TYPE_VPN) {
                    return
                }
            }

            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)
            reload("connectivity changed", this@ServiceSinkhole, false)
        }
    }

    private val networkMonitorCallback = object : ConnectivityManager.NetworkCallback() {
        private val monitorTag = "NetGuard.Monitor"
        private val validated = HashMap<Network, Long>()

        override fun onAvailable(network: Network) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.getNetworkInfo(network)
            val capabilities = cm.getNetworkCapabilities(network)
            Log.i(monitorTag, "Available network $network $ni")
            Log.i(monitorTag, "Capabilities=$capabilities")
            checkConnectivity(network, ni, capabilities)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.getNetworkInfo(network)
            Log.i(monitorTag, "New capabilities network $network $ni")
            Log.i(monitorTag, "Capabilities=$capabilities")
            checkConnectivity(network, ni, capabilities)
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.getNetworkInfo(network)
            Log.i(monitorTag, "Losing network $network within $maxMsToLive ms $ni")
        }

        override fun onLost(network: Network) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val ni = cm.getNetworkInfo(network)
            Log.i(monitorTag, "Lost network $network $ni")

            synchronized(validated) {
                validated.remove(network)
            }
        }

        override fun onUnavailable() {
            Log.i(monitorTag, "No networks available")
        }

        private fun checkConnectivity(network: Network, ni: NetworkInfo?, capabilities: NetworkCapabilities?) {
            if (isActiveNetwork(network) &&
                ni != null && capabilities != null &&
                ni.detailedState != NetworkInfo.DetailedState.SUSPENDED &&
                ni.detailedState != NetworkInfo.DetailedState.BLOCKED &&
                ni.detailedState != NetworkInfo.DetailedState.DISCONNECTED &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {

                synchronized(validated) {
                    if (validated.containsKey(network) &&
                        (validated[network] ?: 0) + 20 * 1000 > Date().time
                    ) {
                        Log.i(monitorTag, "Already validated $network $ni")
                        return
                    }
                }

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                val host = prefs.getString("validate", "www.google.com") ?: "www.google.com"
                Log.i(monitorTag, "Validating $network $ni host=$host")

                var socket: Socket? = null
                try {
                    socket = network.socketFactory.createSocket()
                    socket.connect(InetSocketAddress(host, 443), 10000)
                    Log.i(monitorTag, "Validated $network $ni host=$host")
                    synchronized(validated) {
                        validated[network] = Date().time
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        cm.reportNetworkConnectivity(network, true)
                        Log.i(monitorTag, "Reported $network $ni")
                    }
                } catch (ex: IOException) {
                    Log.e(monitorTag, ex.toString())
                    Log.i(monitorTag, "No connectivity $network $ni")
                } finally {
                    try {
                        socket?.close()
                    } catch (ex: IOException) {
                        Log.e(monitorTag, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }
        }
    }

    private val phoneStateListener = object : PhoneStateListener() {
        private var last_generation: String? = null

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            if (state == TelephonyManager.DATA_CONNECTED) {
                val current_generation = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, "Data connected generation=$current_generation")

                if (last_generation == null || last_generation != current_generation) {
                    Log.i(TAG, "New network generation=$current_generation")
                    last_generation = current_generation

                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    if (prefs.getBoolean("unmetered_2g", false) ||
                        prefs.getBoolean("unmetered_3g", false) ||
                        prefs.getBoolean("unmetered_4g", false)
                    ) {
                        reload("data connection state changed", this@ServiceSinkhole, false)
                    }
                }
            }
        }
    }

    private val packageChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)

            try {
                if (Intent.ACTION_PACKAGE_ADDED == intent.action) {
                    Rule.clearCache(context)

                    if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        if (IAB.isPurchased(ActivityPro.SKU_NOTIFY, context) && prefs.getBoolean("install", true)) {
                            val uid = intent.getIntExtra(Intent.EXTRA_UID, -1)
                            notifyNewApplication(uid, false)
                        }
                    }

                    reload("package added", context, false)

                } else if (Intent.ACTION_PACKAGE_REMOVED == intent.action) {
                    Rule.clearCache(context)

                    if (intent.getBooleanExtra(Intent.EXTRA_DATA_REMOVED, false)) {
                        val packageName = intent.data?.schemeSpecificPart
                        if (packageName != null) {
                            Log.i(TAG, "Deleting settings package=$packageName")
                            context.getSharedPreferences("wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("other", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("screen_other", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("roaming", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("lockdown", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("apply", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                            context.getSharedPreferences("notify", Context.MODE_PRIVATE).edit().remove(packageName).apply()
                        }

                        val uid = intent.getIntExtra(Intent.EXTRA_UID, 0)
                        if (uid > 0) {
                            val dh = DatabaseHelper.getInstance(context)
                            dh.clearLog(uid)
                            dh.clearAccess(uid, false)

                            val nmc = NotificationManagerCompat.from(context)
                            nmc.cancel(uid)
                            nmc.cancel(uid + 10000)
                        }
                    }

                    reload("package deleted", context, false)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }
    }

    private inner class CommandHandler(looper: Looper) : Handler(looper) {
        var queue = 0

        private fun reportQueueSize() {
            val ruleset = Intent(ActivityMain.ACTION_QUEUE_CHANGED)
            ruleset.putExtra(ActivityMain.EXTRA_SIZE, queue)
            LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
        }

        fun queue(intent: Intent) {
            synchronized(this) {
                queue++
                reportQueueSize()
            }
            val cmd = intent.getSerializableExtra(EXTRA_COMMAND) as Command
            val msg = obtainMessage().apply {
                obj = intent
                what = cmd.ordinal
            }
            sendMessage(msg)
        }

        override fun handleMessage(msg: Message) {
            try {
                synchronized(this@ServiceSinkhole) {
                    handleIntent(msg.obj as Intent)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                synchronized(this) {
                    queue--
                    reportQueueSize()
                }
                try {
                    val wl = getLock(this@ServiceSinkhole)
                    if (wl.isHeld) {
                        wl.release()
                    } else {
                        Log.w(TAG, "Wakelock under-locked")
                    }
                    Log.i(TAG, "Messages=${hasMessages(0)} wakelock=${wlInstance?.isHeld}")
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }
        }

        private fun handleIntent(intent: Intent) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)

            val cmd = intent.getSerializableExtra(EXTRA_COMMAND) as Command
            val reason = intent.getStringExtra(EXTRA_REASON)
            Log.i(TAG, "Executing intent=$intent command=$cmd reason=$reason vpn=${vpn != null} user=${Process.myUid() / 100000}")

            if (cmd != Command.stop && !user_foreground) {
                Log.i(TAG, "Command $cmd ignored for background user")
                return
            }

            if (cmd == Command.stop) {
                temporarilyStopped = intent.getBooleanExtra(EXTRA_TEMPORARY, false)
            } else if (cmd == Command.start) {
                temporarilyStopped = false
            } else if (cmd == Command.reload && temporarilyStopped) {
                Log.i(TAG, "Command $cmd ignored because of temporary stop")
                return
            }

            if (prefs.getBoolean("screen_on", true)) {
                if (!registeredInteractiveState) {
                    Log.i(TAG, "Starting listening for interactive state changes")
                    last_interactive = Util.isInteractive(this@ServiceSinkhole)
                    val ifInteractive = IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                        addAction(ACTION_SCREEN_OFF_DELAYED)
                    }
                    ContextCompat.registerReceiver(this@ServiceSinkhole, interactiveStateReceiver, ifInteractive, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registeredInteractiveState = true
                }
            } else {
                if (registeredInteractiveState) {
                    Log.i(TAG, "Stopping listening for interactive state changes")
                    unregisterReceiver(interactiveStateReceiver)
                    registeredInteractiveState = false
                    last_interactive = false
                }
            }

            val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (prefs.getBoolean("disable_on_call", false)) {
                if (tm != null && callStateListener == null && Util.hasPhoneStatePermission(this@ServiceSinkhole)) {
                    Log.i(TAG, "Starting listening for call states")
                    val listener = object : PhoneStateListener() {
                        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                            Log.i(TAG, "New call state=$state")
                            if (prefs.getBoolean("enabled", false)) {
                                if (state == TelephonyManager.CALL_STATE_IDLE) {
                                    start("call state", this@ServiceSinkhole)
                                } else {
                                    stop("call state", this@ServiceSinkhole, true)
                                }
                            }
                        }
                    }
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    callStateListener = listener
                }
            } else {
                if (tm != null && callStateListener != null) {
                    Log.i(TAG, "Stopping listening for call states")
                    tm.listen(callStateListener, PhoneStateListener.LISTEN_NONE)
                    callStateListener = null
                }
            }

            if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                val watchdogIntent = Intent(this@ServiceSinkhole, ServiceSinkhole::class.java).apply {
                    action = ACTION_WATCHDOG
                }
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntentCompat.getForegroundService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                } else {
                    PendingIntentCompat.getService(this@ServiceSinkhole, 1, watchdogIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                }

                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pi)

                if (cmd != Command.stop) {
                    val watchdog = prefs.getString("watchdog", "0")?.toInt() ?: 0
                    if (watchdog > 0) {
                        Log.i(TAG, "Watchdog $watchdog minutes")
                        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + watchdog * 60 * 1000, watchdog.toLong() * 60 * 1000, pi)
                    }
                }
            }

            try {
                when (cmd) {
                    Command.run -> {}
                    Command.start -> start()
                    Command.reload -> reload(intent.getBooleanExtra(EXTRA_INTERACTIVE, false))
                    Command.stop -> stop(temporarilyStopped)
                    Command.stats -> {
                        statsHandler.sendEmptyMessage(MSG_STATS_STOP)
                        statsHandler.sendEmptyMessage(MSG_STATS_START)
                    }
                    Command.householding -> householding()
                    Command.watchdog -> watchdog()
                    else -> Log.e(TAG, "Unknown command=$cmd")
                }

                if (cmd == Command.start || cmd == Command.reload) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val filter = prefs.getBoolean("filter", false)
                        if (filter && isLockdown()) {
                            showLockdownNotification()
                        } else {
                            removeLockdownNotification()
                        }
                    }
                }

                if (cmd == Command.start || cmd == Command.reload || cmd == Command.stop) {
                    val ruleset = Intent(ActivityMain.ACTION_RULES_CHANGED).apply {
                        putExtra(ActivityMain.EXTRA_CONNECTED, if (cmd == Command.stop) false else last_connected)
                        putExtra(ActivityMain.EXTRA_METERED, if (cmd == Command.stop) false else last_metered)
                    }
                    LocalBroadcastManager.getInstance(this@ServiceSinkhole).sendBroadcast(ruleset)
                    WidgetMain.updateWidgets(this@ServiceSinkhole)
                }

                if (!commandHandler.hasMessages(Command.start.ordinal) &&
                    !commandHandler.hasMessages(Command.reload.ordinal) &&
                    !prefs.getBoolean("enabled", false) &&
                    !prefs.getBoolean("show_stats", false)
                ) {
                    stopForeground(true)
                }

                System.gc()
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))

                if (cmd == Command.start || cmd == Command.reload) {
                    if (prepare(this@ServiceSinkhole) == null) {
                        Log.w(TAG, "VPN prepared connected=$last_connected")
                        if (last_connected && ex !is StartFailedException) {
                            if (!Util.isPlayStoreInstall(this@ServiceSinkhole)) {
                                showErrorNotification(ex.toString())
                            }
                        }
                    } else {
                        showErrorNotification(ex.toString())
                        if (ex !is StartFailedException) {
                            prefs.edit().putBoolean("enabled", false).apply()
                            WidgetMain.updateWidgets(this@ServiceSinkhole)
                        }
                    }
                } else {
                    showErrorNotification(ex.toString())
                }
            }
        }

        private fun start() {
            if (vpn == null) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=$state")

                val listRule = Rule.getRules(true, this@ServiceSinkhole)
                val listAllowed = getAllowedRules(listRule)

                last_builder = getBuilder(listAllowed, listRule)
                vpn = startVPN(last_builder!!)
                if (vpn == null) {
                    throw StartFailedException(getString(R.string.msg_start_failed))
                }

                startNative(vpn!!, listAllowed, listRule)

                removeWarningNotifications()
                updateEnforcingNotification(listAllowed.size, listRule.size)
            }
        }

        private fun reload(interactive: Boolean) {
            val listRule = Rule.getRules(true, this@ServiceSinkhole)

            if (interactive) {
                var process = false
                for (rule in listRule) {
                    val blocked = if (last_metered) rule.other_blocked else rule.wifi_blocked
                    val screen = if (last_metered) rule.screen_other else rule.screen_wifi
                    if (blocked && screen) {
                        process = true
                        break
                    }
                }
                if (!process) {
                    Log.i(TAG, "No changed rules on interactive state change")
                    return
                }
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)

            if (state != State.enforcing) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
                state = State.enforcing
                Log.d(TAG, "Start foreground state=$state")
            }

            val listAllowed = getAllowedRules(listRule)
            val builder = getBuilder(listAllowed, listRule)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                last_builder = builder
                Log.i(TAG, "Legacy restart")

                if (vpn != null) {
                    stopNative(vpn!!)
                    stopVPN(vpn!!)
                    vpn = null
                    try {
                        Thread.sleep(500)
                    } catch (ignored: InterruptedException) {
                    }
                }
                vpn = startVPN(last_builder!!)

            } else {
                if (vpn != null && prefs.getBoolean("filter", false) && builder == last_builder) {
                    Log.i(TAG, "Native restart")
                    stopNative(vpn!!)

                } else {
                    last_builder = builder

                    var handover = prefs.getBoolean("handover", false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        handover = false
                    }
                    Log.i(TAG, "VPN restart handover=$handover")

                    if (handover) {
                        val prev = vpn
                        vpn = startVPN(builder)

                        if (prev != null && vpn == null) {
                            Log.w(TAG, "Handover failed")
                            stopNative(prev)
                            stopVPN(prev)
                            try {
                                Thread.sleep(3000)
                            } catch (ignored: InterruptedException) {
                            }
                            vpn = startVPN(last_builder!!)
                            if (vpn == null) {
                                throw IllegalStateException("Handover failed")
                            }
                        }

                        if (prev != null) {
                            stopNative(prev)
                            stopVPN(prev)
                        }
                    } else {
                        if (vpn != null) {
                            stopNative(vpn!!)
                            stopVPN(vpn!!)
                        }
                        vpn = startVPN(builder)
                    }
                }
            }

            if (vpn == null) {
                throw StartFailedException(getString(R.string.msg_start_failed))
            }

            startNative(vpn!!, listAllowed, listRule)

            removeWarningNotifications()
            updateEnforcingNotification(listAllowed.size, listRule.size)
        }

        private fun stop(temporary: Boolean) {
            if (vpn != null) {
                stopNative(vpn!!)
                stopVPN(vpn!!)
                vpn = null
                unprepare()
            }
            if (state == State.enforcing && !temporary) {
                Log.d(TAG, "Stop foreground state=$state")
                last_allowed = -1
                last_blocked = -1
                last_hosts = -1

                stopForeground(true)

                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("show_stats", false)) {
                    startForeground(NOTIFY_WAITING, getWaitingNotification())
                    state = State.waiting
                    Log.d(TAG, "Start foreground state=$state")
                } else {
                    state = State.none
                    stopSelf()
                }
            }
        }

        private fun householding() {
            DatabaseHelper.getInstance(this@ServiceSinkhole).cleanupLog(Date().time - 3 * 24 * 3600 * 1000L)
            DatabaseHelper.getInstance(this@ServiceSinkhole).cleanupDns()

            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            if (!Util.isPlayStoreInstall(this@ServiceSinkhole) &&
                Util.hasValidFingerprint(this@ServiceSinkhole) &&
                prefs.getBoolean("update_check", true)
            ) {
                checkUpdate()
            }
        }

        private fun watchdog() {
            if (vpn == null) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (prefs.getBoolean("enabled", false)) {
                    Log.e(TAG, "Service was killed")
                    start()
                }
            }
        }

        private fun checkUpdate() {
            val json = StringBuilder()
            var urlConnection: HttpsURLConnection? = null
            try {
                val url = URL(BuildConfig.GITHUB_LATEST_API)
                urlConnection = url.openConnection() as HttpsURLConnection
                BufferedReader(InputStreamReader(urlConnection.inputStream)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        json.append(line)
                    }
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            } finally {
                urlConnection?.disconnect()
            }

            try {
                val jroot = JSONObject(json.toString())
                if (jroot.has("tag_name") && jroot.has("html_url") && jroot.has("assets")) {
                    val url = jroot.getString("html_url")
                    val jassets = jroot.getJSONArray("assets")
                    if (jassets.length() > 0) {
                        val jasset = jassets.getJSONObject(0)
                        if (jasset.has("name")) {
                            val version = jroot.getString("tag_name")
                            val name = jasset.getString("name")
                            Log.i(TAG, "Tag $version name $name url $url")

                            val current = Version(Util.getSelfVersionName(this@ServiceSinkhole))
                            val available = Version(version)
                            if (current < available) {
                                Log.i(TAG, "Update available from $current to $available")
                                showUpdateNotification(name, url)
                            } else {
                                Log.i(TAG, "Up-to-date current version $current")
                            }
                        }
                    }
                }
            } catch (ex: JSONException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private inner class StartFailedException(msg: String) : IllegalStateException(msg)
    }

    private inner class LogHandler(looper: Looper) : Handler(looper) {
        var queue = 0

        fun queue(packet: Packet) {
            val msg = obtainMessage().apply {
                obj = packet
                what = MSG_PACKET
                arg1 = if (last_connected) (if (last_metered) 2 else 1) else 0
                arg2 = if (last_interactive) 1 else 0
            }

            synchronized(this) {
                if (queue > 250) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            }
        }

        fun account(usage: Usage) {
            val msg = obtainMessage().apply {
                obj = usage
                what = MSG_USAGE
            }

            synchronized(this) {
                if (queue > 250) {
                    Log.w(TAG, "Log queue full")
                    return
                }
                sendMessage(msg)
                queue++
            }
        }

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_PACKET -> log(msg.obj as Packet, msg.arg1, msg.arg2 > 0)
                    MSG_USAGE -> usage(msg.obj as Usage)
                    else -> Log.e(TAG, "Unknown log message=${msg.what}")
                }
                synchronized(this) {
                    queue--
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private fun log(packet: Packet, connection: Int, interactive: Boolean) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val log = prefs.getBoolean("log", false)
            val log_app = prefs.getBoolean("log_app", false)

            val dh = DatabaseHelper.getInstance(this@ServiceSinkhole)
            val dname = dh.getQName(packet.uid, packet.daddr ?: "")

            if (log) {
                dh.insertLog(packet, dname, connection, interactive)
            }

            if (log_app && packet.uid >= 0 &&
                !(packet.uid == 0 && (packet.protocol == 6 || packet.protocol == 17) && packet.dport == 53)
            ) {
                if (packet.protocol != 6 && packet.protocol != 17) {
                    packet.dport = 0
                }
                if (dh.updateAccess(packet, dname, -1)) {
                    lock.read {
                        if (!mapNotify.containsKey(packet.uid) || mapNotify[packet.uid] == true) {
                            showAccessNotification(packet.uid)
                        }
                    }
                }
            }
        }

        private fun usage(usage: Usage) {
            if (usage.Uid >= 0 && !(usage.Uid == 0 && usage.Protocol == 17 && usage.DPort == 53)) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                val filter = prefs.getBoolean("filter", false)
                val log_app = prefs.getBoolean("log_app", false)
                val track_usage = prefs.getBoolean("track_usage", false)
                if (filter && log_app && track_usage) {
                    val dh = DatabaseHelper.getInstance(this@ServiceSinkhole)
                    val dname = dh.getQName(usage.Uid, usage.DAddr ?: "")
                    Log.i(TAG, "Usage account $usage dname=$dname")
                    dh.updateUsage(usage, dname)
                }
            }
        }
    }

    private inner class StatsHandler(looper: Looper) : Handler(looper) {
        private var statsEnabled = false
        private var whenTime: Long = 0

        private var t: Long = -1
        private var tx: Long = -1
        private var rx: Long = -1

        private val gt = ArrayList<Long>()
        private val gtx = ArrayList<Float>()
        private val grx = ArrayList<Float>()

        private val mapUidBytes = HashMap<Int, Long>()

        override fun handleMessage(msg: Message) {
            try {
                when (msg.what) {
                    MSG_STATS_START -> startStats()
                    MSG_STATS_STOP -> stopStats()
                    MSG_STATS_UPDATE -> updateStats()
                    else -> Log.e(TAG, "Unknown stats message=${msg.what}")
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        private fun startStats() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val enabled = !statsEnabled && prefs.getBoolean("show_stats", false)
            Log.i(TAG, "Stats start enabled=$enabled")
            if (enabled) {
                whenTime = Date().time
                t = -1
                tx = -1
                rx = -1
                gt.clear()
                gtx.clear()
                grx.clear()
                mapUidBytes.clear()
                statsEnabled = true
                updateStats()
            }
        }

        private fun stopStats() {
            Log.i(TAG, "Stats stop")
            statsEnabled = false
            removeMessages(MSG_STATS_UPDATE)
            if (state == State.stats) {
                Log.d(TAG, "Stop foreground state=$state")
                stopForeground(true)
                state = State.none
            } else {
                NotificationManagerCompat.from(this@ServiceSinkhole).cancel(NOTIFY_TRAFFIC)
            }
        }

        @SuppressLint("MissingPermission")
        private fun updateStats() {
            val remoteViews = RemoteViews(packageName, R.layout.traffic)
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
            val frequency = prefs.getString("stats_frequency", "1000")?.toLong() ?: 1000L
            val samples = prefs.getString("stats_samples", "90")?.toLong() ?: 90L
            val filter = prefs.getBoolean("filter", false)
            val show_top = prefs.getBoolean("show_top", false)

            sendEmptyMessageDelayed(MSG_STATS_UPDATE, frequency)

            val ct = SystemClock.elapsedRealtime()

            while (gt.size > 0 && ct - gt[0] > samples * 1000) {
                gt.removeAt(0)
                gtx.removeAt(0)
                grx.removeAt(0)
            }

            var txsec = 0f
            var rxsec = 0f
            var ttx = TrafficStats.getTotalTxBytes()
            var trx = TrafficStats.getTotalRxBytes()
            if (filter) {
                ttx -= TrafficStats.getUidTxBytes(Process.myUid())
                trx -= TrafficStats.getUidRxBytes(Process.myUid())
                if (ttx < 0) ttx = 0
                if (trx < 0) trx = 0
            }
            if (t > 0 && tx > 0 && rx > 0) {
                val dt = (ct - t) / 1000f
                txsec = (ttx - tx) / dt
                rxsec = (trx - rx) / dt
                gt.add(ct)
                gtx.add(txsec)
                grx.add(rxsec)
            }

            if (show_top) {
                if (mapUidBytes.isEmpty()) {
                    for (ainfo in packageManager.getInstalledApplications(0)) {
                        if (ainfo.uid != Process.myUid()) {
                            mapUidBytes[ainfo.uid] = TrafficStats.getUidTxBytes(ainfo.uid) + TrafficStats.getUidRxBytes(ainfo.uid)
                        }
                    }
                } else if (t > 0) {
                    val mapSpeedUid = TreeMap<Float, Int> { value, other -> -value.compareTo(other) }
                    val dt = (ct - t) / 1000f
                    for (uid in mapUidBytes.keys) {
                        val bytes = TrafficStats.getUidTxBytes(uid) + TrafficStats.getUidRxBytes(uid)
                        val speed = (bytes - (mapUidBytes[uid] ?: 0L)) / dt
                        if (speed > 0) {
                            mapSpeedUid[speed] = uid
                            mapUidBytes[uid] = bytes
                        }
                    }

                    val sb = StringBuilder()
                    var i = 0
                    for (speed in mapSpeedUid.keys) {
                        if (i++ >= 3) break
                        if (speed < 1000 * 1000) {
                            sb.append(getString(R.string.msg_kbsec, speed / 1000))
                        } else {
                            sb.append(getString(R.string.msg_mbsec, speed / 1000 / 1000))
                        }
                        sb.append(' ')
                        val apps = Util.getApplicationNames(mapSpeedUid[speed] ?: 0, this@ServiceSinkhole)
                        sb.append(if (apps.isNotEmpty()) apps[0] else "?")
                        sb.append("\r\n")
                    }
                    if (sb.isNotEmpty()) sb.setLength(sb.length - 2)
                    remoteViews.setTextViewText(R.id.tvTop, sb.toString())
                }
            }

            t = ct
            tx = ttx
            rx = trx

            val height = Util.dips2pixels(96, this@ServiceSinkhole)
            val width = Util.dips2pixels(96 * 5, this@ServiceSinkhole)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            var max = 0f
            var xmax: Long = 0
            var ymax = 0f
            for (i in gt.indices) {
                val time = gt[i]
                val currentTx = gtx[i]
                val currentRx = grx[i]
                if (time > xmax) xmax = time
                if (currentTx > max) max = currentTx
                if (currentRx > max) max = currentRx
                if (currentTx > ymax) ymax = currentTx
                if (currentRx > ymax) ymax = currentRx
            }

            val ptx = Path()
            val prx = Path()
            for (i in gtx.indices) {
                val x = width - width * (xmax - gt[i]) / 1000f / samples
                val ytx = height - height * gtx[i] / ymax
                val yrx = height - height * grx[i] / ymax
                if (i == 0) {
                    ptx.moveTo(x, ytx)
                    prx.moveTo(x, yrx)
                } else {
                    ptx.lineTo(x, ytx)
                    prx.lineTo(x, yrx)
                }
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

            paint.strokeWidth = Util.dips2pixels(1, this@ServiceSinkhole).toFloat()
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorGrayed)
            val yLine = height / 2f
            canvas.drawLine(0f, yLine, width.toFloat(), yLine, paint)

            paint.strokeWidth = Util.dips2pixels(2, this@ServiceSinkhole).toFloat()
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorSend)
            canvas.drawPath(ptx, paint)
            paint.color = ContextCompat.getColor(this@ServiceSinkhole, R.color.colorReceive)
            canvas.drawPath(prx, paint)

            remoteViews.setImageViewBitmap(R.id.ivTraffic, bitmap)
            remoteViews.setTextViewText(R.id.tvTx, if (txsec < 1000 * 1000) getString(R.string.msg_kbsec, txsec / 1000) else getString(R.string.msg_mbsec, txsec / 1000 / 1000))
            remoteViews.setTextViewText(R.id.tvRx, if (rxsec < 1000 * 1000) getString(R.string.msg_kbsec, rxsec / 1000) else getString(R.string.msg_mbsec, rxsec / 1000 / 1000))
            remoteViews.setTextViewText(R.id.tvMax, if (max < 1000 * 1000) getString(R.string.msg_kbsec, max / 2 / 1000) else getString(R.string.msg_mbsec, max / 2 / 1000 / 1000))

            if (BuildConfig.DEBUG) {
                val count = jni_get_stats(jni_context)
                remoteViews.setTextViewText(R.id.tvSessions, "${count[0]}/${count[1]}/${count[2]}")
                remoteViews.setTextViewText(R.id.tvFiles, "${count[3]}/${count[4]}")
            } else {
                remoteViews.setTextViewText(R.id.tvSessions, "")
                remoteViews.setTextViewText(R.id.tvFiles, "")
            }

            val main = Intent(this@ServiceSinkhole, ActivityMain::class.java)
            val pi = PendingIntentCompat.getActivity(this@ServiceSinkhole, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)

            val tv = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder = NotificationCompat.Builder(this@ServiceSinkhole, "notify")
                .setWhen(whenTime)
                .setSmallIcon(R.drawable.ic_equalizer_white_24dp)
                .setContent(remoteViews)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setOngoing(true)
                .setAutoCancel(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }

            if (state == State.none || state == State.waiting) {
                if (state != State.none) {
                    Log.d(TAG, "Stop foreground state=$state")
                    stopForeground(true)
                }
                startForeground(NOTIFY_TRAFFIC, builder.build())
                state = State.stats
                Log.d(TAG, "Start foreground state=$state")
            } else {
                if (Util.canNotify(this@ServiceSinkhole)) {
                    val notification = builder.build()
                    NotificationManagerCompat.from(this@ServiceSinkhole).notify(NOTIFY_TRAFFIC, notification)
                }
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "Create version=${Util.getSelfVersionName(this)}/${Util.getSelfVersionCode(this)}")
        startForeground(NOTIFY_WAITING, getWaitingNotification())

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (jni_context != 0L) {
            Log.w(TAG, "Create with context=$jni_context")
            jni_stop(jni_context)
            synchronized(jni_lock) {
                jni_done(jni_context)
                jni_context = 0
            }
        }

        jni_context = jni_init(Build.VERSION.SDK_INT)
        Log.i(TAG, "Created context=$jni_context")
        setPcap(prefs.getBoolean("pcap", false), this)

        prefs.registerOnSharedPreferenceChangeListener(this)
        Util.setTheme(this)
        super.onCreate()

        val commandThread = HandlerThread(getString(R.string.app_name) + " command", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        val logThread = HandlerThread(getString(R.string.app_name) + " log", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val statsThread = HandlerThread(getString(R.string.app_name) + " stats", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }

        commandLooper = commandThread.looper
        logLooper = logThread.looper
        statsLooper = statsThread.looper

        commandHandler = CommandHandler(commandLooper)
        logHandler = LogHandler(logLooper)
        statsHandler = StatsHandler(statsLooper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            val ifUser = IntentFilter().apply {
                addAction(Intent.ACTION_USER_BACKGROUND)
                addAction(Intent.ACTION_USER_FOREGROUND)
            }
            ContextCompat.registerReceiver(this, userReceiver, ifUser, ContextCompat.RECEIVER_NOT_EXPORTED)
            registeredUser = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ifIdle = IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            ContextCompat.registerReceiver(this, idleStateReceiver, ifIdle, ContextCompat.RECEIVER_NOT_EXPORTED)
            registeredIdleState = true
        }

        val ifAp = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        ContextCompat.registerReceiver(this, apStateReceiver, ifAp, ContextCompat.RECEIVER_NOT_EXPORTED)
        registeredApState = true

        val ifPackage = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChangedReceiver, ifPackage, ContextCompat.RECEIVER_NOT_EXPORTED)
        registeredPackageChanged = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                listenNetworkChanges()
            } catch (ex: Throwable) {
                Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                listenConnectivityChanges()
            }
        } else {
            listenConnectivityChanges()
        }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
            networkMonitorCallback
        )

        val alarmIntent = Intent(this, ServiceSinkhole::class.java).apply { action = ACTION_HOUSE_HOLDING }
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntentCompat.getForegroundService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntentCompat.getService(this, 0, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setInexactRepeating(AlarmManager.RTC, SystemClock.elapsedRealtime() + 60 * 1000, AlarmManager.INTERVAL_HALF_DAY, pi)
    }

    private fun listenConnectivityChanges() {
        Log.i(TAG, "Starting listening to connectivity changes")
        val ifConnectivity = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        ContextCompat.registerReceiver(this, connectivityChangedReceiver, ifConnectivity, ContextCompat.RECEIVER_NOT_EXPORTED)
        registeredConnectivityChanged = true

        Log.i(TAG, "Starting listening to service state changes")
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.listen(phoneStateListener, PhoneStateListener.LISTEN_DATA_CONNECTION_STATE)
        phone_state = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (state == State.enforcing) {
            startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
        } else {
            startForeground(NOTIFY_WAITING, getWaitingNotification())
        }

        Log.i(TAG, "Received $intent")
        Util.logExtras(intent)

        if (intent != null && intent.hasExtra(EXTRA_COMMAND) && intent.getSerializableExtra(EXTRA_COMMAND) == Command.set) {
            set(intent)
            return START_STICKY
        }

        getLock(this).acquire()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean("enabled", false)

        var finalIntent = intent
        if (finalIntent == null) {
            Log.i(TAG, "Restart")
            finalIntent = Intent(this, ServiceSinkhole::class.java).apply {
                putExtra(EXTRA_COMMAND, if (enabled) Command.start else Command.stop)
            }
        }

        if (ACTION_HOUSE_HOLDING == finalIntent.action) {
            finalIntent.putExtra(EXTRA_COMMAND, Command.householding)
        }
        if (ACTION_WATCHDOG == finalIntent.action) {
            finalIntent.putExtra(EXTRA_COMMAND, Command.watchdog)
        }

        if (finalIntent.getSerializableExtra(EXTRA_COMMAND) == null) {
            finalIntent.putExtra(EXTRA_COMMAND, if (enabled) Command.start else Command.stop)
        }

        val cmd = finalIntent.getSerializableExtra(EXTRA_COMMAND) as Command
        val reason = finalIntent.getStringExtra(EXTRA_REASON)
        Log.i(TAG, "Start intent=$finalIntent command=$cmd reason=$reason vpn=${vpn != null} user=${Process.myUid() / 100000}")

        commandHandler.queue(finalIntent)
        return START_STICKY
    }

    private fun set(intent: Intent) {
        val uid = intent.getIntExtra(EXTRA_UID, 0)
        val network = intent.getStringExtra(EXTRA_NETWORK)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val blocked = intent.getBooleanExtra(EXTRA_BLOCKED, false)
        Log.i(TAG, "Set $pkg $network=$blocked")

        val settings = PreferenceManager.getDefaultSharedPreferences(this)
        val defaultWifi = settings.getBoolean("whitelist_wifi", true)
        val defaultOther = settings.getBoolean("whitelist_other", true)

        val prefs = getSharedPreferences(network, Context.MODE_PRIVATE)
        if (blocked == (if ("wifi" == network) defaultWifi else defaultOther)) {
            prefs.edit().remove(pkg).apply()
        } else {
            prefs.edit().putBoolean(pkg, blocked).apply()
        }

        reload("notification", this, false)
        notifyNewApplication(uid, false)

        val ruleset = Intent(ActivityMain.ACTION_RULES_CHANGED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(ruleset)
    }

    override fun onRevoke() {
        Log.i(TAG, "Revoke")
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("enabled", false).apply()
        showDisabledNotification()
        WidgetMain.updateWidgets(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        synchronized(this) {
            Log.i(TAG, "Destroy")
            commandLooper.quit()
            logLooper.quit()
            statsLooper.quit()

            for (command in Command.values()) {
                commandHandler.removeMessages(command.ordinal)
            }
            releaseLock(this)

            if (registeredInteractiveState) {
                unregisterReceiver(interactiveStateReceiver)
                registeredInteractiveState = false
            }
            callStateListener?.let {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.listen(it, PhoneStateListener.LISTEN_NONE)
                callStateListener = null
            }

            if (registeredUser) {
                unregisterReceiver(userReceiver)
                registeredUser = false
            }
            if (registeredIdleState) {
                unregisterReceiver(idleStateReceiver)
                registeredIdleState = false
            }
            if (registeredApState) {
                unregisterReceiver(apStateReceiver)
                registeredApState = false
            }
            if (registeredPackageChanged) {
                unregisterReceiver(packageChangedReceiver)
                registeredPackageChanged = false
            }

            if (networkCallback != null) {
                unlistenNetworkChanges()
                networkCallback = null
            }
            if (registeredConnectivityChanged) {
                unregisterReceiver(connectivityChangedReceiver)
                registeredConnectivityChanged = false
            }

            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkMonitorCallback)

            if (phone_state) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
                phone_state = false
            }

            try {
                vpn?.let {
                    stopNative(it)
                    stopVPN(it)
                    vpn = null
                    unprepare()
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }

            Log.i(TAG, "Destroy context=$jni_context")
            synchronized(jni_lock) {
                jni_done(jni_context)
                jni_context = 0
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
        super.onDestroy()
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun listenNetworkChanges() {
        Log.i(TAG, "Starting listening to network changes")
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = NetworkRequest.Builder().apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        val nc = object : ConnectivityManager.NetworkCallback() {
            private var lastActive: Network? = null
            private var lastNetwork: Network? = null
            private var lastConnected: Boolean? = null
            private var lastMetered: Boolean? = null
            private var lastGeneration: String? = null
            private var lastDns: List<InetAddress>? = null

            override fun onAvailable(network: Network) {
                Log.i(TAG, "Available network=$network")
                if (!isActiveNetwork(network)) return
                lastActive = network
                lastConnected = Util.isConnected(this@ServiceSinkhole)
                lastMetered = Util.isMeteredNetwork(this@ServiceSinkhole)
                reload("network available", this@ServiceSinkhole, false)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.i(TAG, "Changed properties=$network props=$linkProperties")
                if (!isActiveNetwork(network)) return
                val dns = linkProperties.dnsServers
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) !same(lastDns, dns) else prefs.getBoolean("reload_onconnectivity", false)) {
                    Log.i(TAG, "Changed link properties=$linkProperties DNS cur=${TextUtils.join(",", dns)} DNS prv=${if (lastDns == null) null else TextUtils.join(",", lastDns!!)}")
                    lastDns = dns
                    reload("link properties changed", this@ServiceSinkhole, false)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                Log.i(TAG, "Changed capabilities=$network caps=$capabilities")
                if (!isActiveNetwork(network)) return
                val connected = Util.isConnected(this@ServiceSinkhole)
                val metered = Util.isMeteredNetwork(this@ServiceSinkhole)
                val generation = Util.getNetworkGeneration(this@ServiceSinkhole)
                Log.i(TAG, "Connected=$connected/$lastConnected unmetered=$metered/$lastMetered generation=$generation/$lastGeneration")

                var reason: String? = null
                if (network != lastNetwork) reason = "Network changed"
                if (reason == null && lastConnected != null && lastConnected != connected) reason = "Connected state changed"
                if (reason == null && lastMetered != null && lastMetered != metered) reason = "Unmetered state changed"
                if (reason == null && lastGeneration != null && lastGeneration != generation) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole)
                    if (prefs.getBoolean("unmetered_2g", false) || prefs.getBoolean("unmetered_3g", false) || prefs.getBoolean("unmetered_4g", false)) {
                        reason = "Generation changed"
                    }
                }

                reason?.let { reload(it, this@ServiceSinkhole, false) }
                lastNetwork = network
                lastConnected = connected
                lastMetered = metered
                lastGeneration = generation
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Lost network=$network active=${isActiveNetwork(network)}")
                if (lastActive != network) return
                lastActive = null
                lastConnected = Util.isConnected(this@ServiceSinkhole)
                reload("network lost", this@ServiceSinkhole, false)
            }

            private fun same(last: List<InetAddress>?, current: List<InetAddress>?): Boolean {
                if (last == null || current == null) return false
                if (last.size != current.size) return false
                for (i in current.indices) {
                    if (last[i] != current[i]) return false
                }
                return true
            }
        }
        cm.registerNetworkCallback(builder.build(), nc)
        networkCallback = nc
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun unlistenNetworkChanges() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun getActiveNetwork(): Network? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(active)
            return if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) active else null
        }

        val ani = cm.activeNetworkInfo ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
            val ni = cm.getNetworkInfo(network) ?: continue
            if (ni.type == ani.type && ni.subtype == ani.subtype) return network
        }
        return null
    }

    private fun isActiveNetwork(network: Network?): Boolean {
        return network != null && network == getActiveNetwork()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, name: String?) {
        val p = prefs ?: return
        if ("theme" == name) {
            Log.i(TAG, "Theme changed")
            Util.setTheme(this)
            if (state != State.none) {
                Log.d(TAG, "Stop foreground state=$state")
                stopForeground(true)
            }
            if (state == State.enforcing) {
                startForeground(NOTIFY_ENFORCING, getEnforcingNotification(-1, -1, -1))
            } else if (state != State.none) {
                startForeground(NOTIFY_WAITING, getWaitingNotification())
            }
            Log.d(TAG, "Start foreground state=$state")
        }
    }

    private fun startVPN(builder: Builder): ParcelFileDescriptor? {
        return try {
            builder.establish()
        } catch (ex: SecurityException) {
            throw ex
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            null
        }
    }

    private fun getBuilder(listAllowed: List<Rule>, listRule: List<Rule>): Builder {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val subnet = prefs.getBoolean("subnet", false)
        val tethering = prefs.getBoolean("tethering", false)
        val lan = prefs.getBoolean("lan", false)
        val ip6 = prefs.getBoolean("ip6", true)
        val filter = prefs.getBoolean("filter", false)
        val system = prefs.getBoolean("manage_system", false)

        val builder = Builder()
        builder.setSession(getString(R.string.app_name))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(Util.isMeteredNetwork(this))
        }

        val vpn4 = prefs.getString("vpn4", "10.1.10.1") ?: "10.1.10.1"
        builder.addAddress(vpn4, 32)
        if (ip6) {
            val vpn6 = prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1") ?: "fd00:1:fd00:1:fd00:1:fd00:1"
            builder.addAddress(vpn6, 128)
        }

        if (filter) {
            for (dns in getDns(this)) {
                if (ip6 || dns is Inet4Address) {
                    builder.addDnsServer(dns)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val domain = cm.getLinkProperties(cm.activeNetwork)?.domains
                if (domain != null) {
                    builder.addSearchDomain(domain)
                }
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        }

        if (subnet) {
            val listExclude = ArrayList<IPUtil.CIDR>()
            listExclude.add(IPUtil.CIDR("127.0.0.0", 8))
            if (tethering && !lan) {
                listExclude.add(IPUtil.CIDR("192.168.42.0", 23))
                listExclude.add(IPUtil.CIDR("192.168.44.0", 24))
                listExclude.add(IPUtil.CIDR("192.168.49.0", 24))
                try {
                    NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { ni ->
                        if (!ni.isLoopback && ni.isUp && ni.name?.startsWith("ap_br_wlan") == true) {
                            ni.interfaceAddresses.forEach { ia ->
                                if (ia.address is Inet4Address) listExclude.add(IPUtil.CIDR(ia.address.hostAddress, 24))
                            }
                        }
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString())
                }
            }
            if (lan) {
                listExclude.add(IPUtil.CIDR("10.0.0.0", 8))
                listExclude.add(IPUtil.CIDR("172.16.0.0", 12))
                listExclude.add(IPUtil.CIDR("192.168.0.0", 16))
            }
            if (!filter) {
                getDns(this).forEach { if (it is Inet4Address) listExclude.add(IPUtil.CIDR(it.hostAddress, 32)) }
                val dns_specifier = Util.getPrivateDnsSpecifier(this)
                if (!TextUtils.isEmpty(dns_specifier)) {
                    try {
                        InetAddress.getAllByName(dns_specifier).forEach { if (it is Inet4Address) listExclude.add(IPUtil.CIDR(it.hostAddress, 32)) }
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString())
                    }
                }
            }

            val config = resources.configuration
            if (config.mcc == 310 && config.mnc in intArrayOf(160, 200, 210, 220, 230, 240, 250, 260, 270, 310, 490, 660, 800)) {
                listExclude.add(IPUtil.CIDR("66.94.2.0", 24))
                listExclude.add(IPUtil.CIDR("66.94.6.0", 23))
                listExclude.add(IPUtil.CIDR("66.94.8.0", 22))
                listExclude.add(IPUtil.CIDR("208.54.0.0", 16))
            }
            if ((config.mcc == 310 && config.mnc in intArrayOf(4, 5, 6, 10, 12, 13, 350, 590, 820, 890, 910)) ||
                (config.mcc == 311 && (config.mnc in intArrayOf(12, 110, 390, 590) || config.mnc in 270..289 || config.mnc in 480..489)) ||
                (config.mcc == 312 && config.mnc == 770)
            ) {
                listExclude.add(IPUtil.CIDR("66.174.0.0", 16))
                listExclude.add(IPUtil.CIDR("66.82.0.0", 15))
                listExclude.add(IPUtil.CIDR("69.96.0.0", 13))
                listExclude.add(IPUtil.CIDR("70.192.0.0", 11))
                listExclude.add(IPUtil.CIDR("97.128.0.0", 9))
                listExclude.add(IPUtil.CIDR("174.192.0.0", 9))
                listExclude.add(IPUtil.CIDR("72.96.0.0", 9))
                listExclude.add(IPUtil.CIDR("75.192.0.0", 9))
                listExclude.add(IPUtil.CIDR("97.0.0.0", 10))
            }
            if (config.mnc == 10 && config.mcc == 208) listExclude.add(IPUtil.CIDR("10.151.0.0", 24))
            listExclude.add(IPUtil.CIDR("224.0.0.0", 3))
            Collections.sort(listExclude)
            try {
                var start = InetAddress.getByName("0.0.0.0")
                for (exclude in listExclude) {
                    val excludeStart = exclude.start ?: continue
                    for (include in IPUtil.toCIDR(start, IPUtil.minus1(excludeStart)!!)) {
                        try {
                            builder.addRoute(include.address, include.prefix)
                        } catch (ex: Throwable) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        }
                    }
                    val excludeEnd = exclude.end ?: continue
                    start = IPUtil.plus1(excludeEnd)
                }
                val end = if (lan) "255.255.255.254" else "255.255.255.255"
                for (include in IPUtil.toCIDR("224.0.0.0", end)) {
                    try {
                        builder.addRoute(include.address, include.prefix)
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            } catch (ex: UnknownHostException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        if (ip6) builder.addRoute("2000::", 3)
        val mtu = jni_get_mtu()
        builder.setMtu(mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (last_connected && !filter) {
                val mapDisallowed = HashMap<String, Rule>()
                listRule.forEach { it.packageName?.let { pkg -> mapDisallowed[pkg] = it } }
                listAllowed.forEach { it.packageName?.let { pkg -> mapDisallowed.remove(pkg) } }
                mapDisallowed.keys.forEach { try { builder.addAllowedApplication(it) } catch (ex: PackageManager.NameNotFoundException) { Log.e(TAG, ex.toString()) } }
                if (mapDisallowed.isEmpty()) try { builder.addAllowedApplication(packageName) } catch (ex: PackageManager.NameNotFoundException) { Log.e(TAG, ex.toString()) }
            } else if (filter) {
                try { builder.addDisallowedApplication(packageName) } catch (ex: PackageManager.NameNotFoundException) { Log.e(TAG, ex.toString()) }
                listRule.forEach { if (!it.apply || (!system && it.system)) it.packageName?.let { pkg -> try { builder.addDisallowedApplication(pkg) } catch (ex: PackageManager.NameNotFoundException) { Log.e(TAG, ex.toString()) } } }
            }
        }

        val configure = Intent(this, ActivityMain::class.java)
        builder.setConfigureIntent(PendingIntentCompat.getActivity(this, 0, configure, PendingIntent.FLAG_UPDATE_CURRENT))
        return builder
    }

    private fun startNative(vpn: ParcelFileDescriptor, listAllowed: List<Rule>, listRule: List<Rule>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val log = prefs.getBoolean("log", false)
        val log_app = prefs.getBoolean("log_app", false)
        val filter = prefs.getBoolean("filter", false)

        if (filter) {
            prepareUidAllowed(listAllowed, listRule)
            prepareHostsBlocked()
            prepareMalwareList()
            prepareUidIPFilters(null)
            prepareForwarding()
        } else {
            lock.write {
                mapUidAllowed.clear()
                mapUidKnown.clear()
                mapHostsBlocked.clear()
                mapMalware.clear()
                mapUidIPFilters.clear()
                mapForward.clear()
            }
        }

        if (log_app) prepareNotify(listRule) else lock.write { mapNotify.clear() }

        if (log || log_app || filter) {
            val prio = prefs.getString("loglevel", Log.WARN.toString())?.toInt() ?: Log.WARN
            val rcode = prefs.getString("rcode", "3")?.toInt() ?: 3
            if (prefs.getBoolean("socks5_enabled", false)) {
                jni_socks5(prefs.getString("socks5_addr", "") ?: "", prefs.getString("socks5_port", "0")?.toInt() ?: 0, prefs.getString("socks5_username", "") ?: "", prefs.getString("socks5_password", "") ?: "")
            } else {
                jni_socks5("", 0, "", "")
            }

            if (tunnelThread == null) {
                jni_start(jni_context, prio)
                tunnelThread = Thread {
                    jni_run(jni_context, vpn.fd, mapForward.containsKey(53), rcode)
                    tunnelThread = null
                }.apply { start() }
            }
        }
    }

    private fun stopNative(vpn: ParcelFileDescriptor) {
        if (tunnelThread != null) {
            jni_stop(jni_context)
            val thread = tunnelThread
            while (thread != null && thread.isAlive) {
                try {
                    thread.join()
                } catch (ignored: InterruptedException) {
                }
            }
            tunnelThread = null
            jni_clear(jni_context)
        }
    }

    private fun unprepare() {
        lock.write {
            mapUidAllowed.clear()
            mapUidKnown.clear()
            mapHostsBlocked.clear()
            mapMalware.clear()
            mapUidIPFilters.clear()
            mapForward.clear()
            mapNotify.clear()
        }
    }

    private fun prepareUidAllowed(listAllowed: List<Rule>, listRule: List<Rule>) {
        lock.write {
            mapUidAllowed.clear()
            listAllowed.forEach { mapUidAllowed[it.uid] = true }
            mapUidKnown.clear()
            listRule.forEach { mapUidKnown[it.uid] = it.uid }
        }
    }

    private fun prepareHostsBlocked() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val use_hosts = prefs.getBoolean("filter", false) && prefs.getBoolean("use_hosts", false)
        val hosts = File(filesDir, "hosts.txt")
        if (!use_hosts || !hosts.exists() || !hosts.canRead()) {
            lock.write { mapHostsBlocked.clear() }
            return
        }
        if (hosts.lastModified() == last_hosts_modified && mapHostsBlocked.isNotEmpty()) return
        last_hosts_modified = hosts.lastModified()
        lock.write {
            mapHostsBlocked.clear()
            try {
                BufferedReader(FileReader(hosts)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val hash = line!!.indexOf('#')
                        val l = if (hash >= 0) line!!.substring(0, hash).trim() else line!!.trim()
                        if (l.isNotEmpty()) {
                            val words = l.split("\\s+".toRegex()).toTypedArray()
                            if (words.size == 2) mapHostsBlocked[words[1]] = true
                        }
                    }
                }
                mapHostsBlocked["test.netguard.me"] = true
            } catch (ex: IOException) {
                Log.e(TAG, ex.toString())
            }
        }
    }

    private fun prepareMalwareList() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val malware = prefs.getBoolean("filter", false) && prefs.getBoolean("malware", false)
        val file = File(filesDir, "malware.txt")
        if (!malware || !file.exists() || !file.canRead()) {
            lock.write { mapMalware.clear() }
            return
        }
        if (file.lastModified() == last_malware_modified && mapMalware.isNotEmpty()) return
        last_malware_modified = file.lastModified()
        lock.write {
            mapMalware.clear()
            try {
                BufferedReader(FileReader(file)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        val hash = line!!.indexOf('#')
                        val l = if (hash >= 0) line!!.substring(0, hash).trim() else line!!.trim()
                        if (l.isNotEmpty()) {
                            val words = l.split("\\s+".toRegex()).toTypedArray()
                            if (words.size > 1) mapMalware[words[1]] = true
                        }
                    }
                }
            } catch (ex: IOException) {
                Log.e(TAG, ex.toString())
            }
        }
    }

    private fun prepareUidIPFilters(dname: String?) {
        val lockdown = getSharedPreferences("lockdown", Context.MODE_PRIVATE)
        lock.write {
            if (dname == null) {
                mapUidIPFilters.clear()
                if (!IAB.isPurchased(ActivityPro.SKU_FILTER, this@ServiceSinkhole)) return@write
            }
            DatabaseHelper.getInstance(this@ServiceSinkhole).getAccessDns(dname).use { cursor ->
                val colUid = cursor.getColumnIndex("uid")
                val colVersion = cursor.getColumnIndex("version")
                val colProtocol = cursor.getColumnIndex("protocol")
                val colDAddr = cursor.getColumnIndex("daddr")
                val colResource = cursor.getColumnIndex("resource")
                val colDPort = cursor.getColumnIndex("dport")
                val colBlock = cursor.getColumnIndex("block")
                val colTime = cursor.getColumnIndex("time")
                val colTTL = cursor.getColumnIndex("ttl")
                while (cursor.moveToNext()) {
                    val uid = cursor.getInt(colUid)
                    val version = cursor.getInt(colVersion)
                    val protocol = cursor.getInt(colProtocol)
                    val daddr = cursor.getString(colDAddr)
                    val dresource = if (cursor.isNull(colResource)) null else cursor.getString(colResource)
                    val dport = cursor.getInt(colDPort)
                    val block = cursor.getInt(colBlock) > 0
                    val time = if (cursor.isNull(colTime)) Date().time else cursor.getLong(colTime)
                    val ttl = if (cursor.isNull(colTTL)) 7 * 24 * 3600 * 1000L else cursor.getLong(colTTL)

                    if (isLockedDown(last_metered)) {
                        val pkg = packageManager.getPackagesForUid(uid)
                        if (pkg != null && pkg.isNotEmpty() && !lockdown.getBoolean(pkg[0], false)) continue
                    }

                    val key = IPKey(version, protocol, dport, uid)
                    synchronized(mapUidIPFilters) {
                        val map = mapUidIPFilters.getOrPut(key) { HashMap() }
                        try {
                            val name = dresource ?: daddr
                            if (Util.isNumericAddress(name)) {
                                val iname = InetAddress.getByName(name)
                                if (version == 4 && iname !is Inet4Address) return@synchronized
                                if (version == 6 && iname !is Inet6Address) return@synchronized
                                val exists = map.containsKey(iname)
                                val existingRule = map[iname]
                                if (!exists || existingRule?.isBlocked() == false) {
                                    map[iname] = IPRule(key, "$name/$iname", block, time, ttl)
                                } else if (exists) {
                                    existingRule?.updateExpires(time, ttl)
                                }
                            }
                        } catch (ex: UnknownHostException) {
                            Log.e(TAG, ex.toString())
                        }
                    }
                }
            }
        }
    }

    private fun prepareForwarding() {
        lock.write {
            mapForward.clear()
            if (PreferenceManager.getDefaultSharedPreferences(this@ServiceSinkhole).getBoolean("filter", false)) {
                DatabaseHelper.getInstance(this@ServiceSinkhole).forwarding.use { cursor ->
                    val colProtocol = cursor.getColumnIndex("protocol")
                    val colDPort = cursor.getColumnIndex("dport")
                    val colRAddr = cursor.getColumnIndex("raddr")
                    val colRPort = cursor.getColumnIndex("rport")
                    val colRUid = cursor.getColumnIndex("ruid")
                    while (cursor.moveToNext()) {
                        val fwd = Forward().apply {
                            protocol = cursor.getInt(colProtocol)
                            dport = cursor.getInt(colDPort)
                            raddr = cursor.getString(colRAddr)
                            rport = cursor.getInt(colRPort)
                            ruid = cursor.getInt(colRUid)
                        }
                        mapForward[fwd.dport] = fwd
                    }
                }
            }
        }
    }

    private fun prepareNotify(listRule: List<Rule>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val notify = prefs.getBoolean("notify_access", false)
        val system = prefs.getBoolean("manage_system", false)
        lock.write {
            mapNotify.clear()
            listRule.forEach { mapNotify[it.uid] = notify && it.notify && (system || !it.system) }
        }
    }

    private fun isLockedDown(metered: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        var lockdown = prefs.getBoolean("lockdown", false)
        val lockdown_wifi = prefs.getBoolean("lockdown_wifi", true)
        val lockdown_other = prefs.getBoolean("lockdown_other", true)
        if (if (metered) !lockdown_other else !lockdown_wifi) lockdown = false
        return lockdown
    }

    private fun getAllowedRules(listRule: List<Rule>): List<Rule> {
        val listAllowed = ArrayList<Rule>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val wifi = Util.isWifiActive(this)
        var metered = Util.isMeteredNetwork(this)
        val useMetered = prefs.getBoolean("use_metered", false)
        val ssidHomes = prefs.getStringSet("wifi_homes", HashSet()) ?: HashSet()
        val ssidNetwork = Util.getWifiSSID(this)
        val generation = Util.getNetworkGeneration(this)
        val unmetered_2g = prefs.getBoolean("unmetered_2g", false)
        val unmetered_3g = prefs.getBoolean("unmetered_3g", false)
        val unmetered_4g = prefs.getBoolean("unmetered_4g", false)
        var roaming = Util.isRoaming(this)
        val national = prefs.getBoolean("national_roaming", false)
        val eu = prefs.getBoolean("eu_roaming", false)

        last_connected = Util.isConnected(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ssidHomes.clear()
        if (wifi && !useMetered) metered = false
        if (wifi && ssidHomes.isNotEmpty() && !(ssidHomes.contains(ssidNetwork) || ssidHomes.contains("\"$ssidNetwork\""))) {
            metered = true
        }
        if ((unmetered_2g && "2G" == generation) || (unmetered_3g && "3G" == generation) || (unmetered_4g && "4G" == generation)) metered = false
        last_metered = metered

        val lockdown = isLockedDown(last_metered)
        if (roaming && eu) roaming = !Util.isEU(this)
        if (roaming && national) roaming = !Util.isNational(this)

        if (last_connected) {
            for (rule in listRule) {
                val blocked = if (metered) rule.other_blocked else rule.wifi_blocked
                val screen = if (metered) rule.screen_other else rule.screen_wifi
                if ((!blocked || (screen && last_interactive)) && (!metered || !(rule.roaming && roaming)) && (!lockdown || rule.lockdown)) {
                    listAllowed.add(rule)
                }
            }
        }
        return listAllowed
    }

    private fun stopVPN(pfd: ParcelFileDescriptor) {
        try { pfd.close() } catch (ex: IOException) { Log.e(TAG, ex.toString()) }
    }

    private fun nativeExit(reason: String?) {
        if (reason != null) {
            showErrorNotification(reason)
            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("enabled", false).apply()
            WidgetMain.updateWidgets(this)
        }
    }

    private fun nativeError(error: Int, message: String) {
        showErrorNotification(message)
    }

    private fun logPacket(packet: Packet) {
        logHandler.queue(packet)
    }

    private fun dnsResolved(rr: ResourceRecord) {
        if (DatabaseHelper.getInstance(this).insertDns(rr)) {
            prepareUidIPFilters(rr.QName)
        }
        if (rr.uid > 0 && !TextUtils.isEmpty(rr.AName)) {
            val malware = lock.read { mapMalware[rr.AName] == true }
            if (malware) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                if (!prefs.getBoolean("malware.${rr.uid}", false)) {
                    prefs.edit().putBoolean("malware.${rr.uid}", true).apply()
                    notifyNewApplication(rr.uid, true)
                }
            }
        }
    }

    private fun isDomainBlocked(name: String): Boolean {
        return lock.read { mapHostsBlocked[name] == true }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun getUidQ(version: Int, protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 && protocol != 17) return Process.INVALID_UID
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Process.INVALID_UID
        return cm.getConnectionOwnerUid(protocol, InetSocketAddress(saddr, sport), InetSocketAddress(daddr, dport))
    }

    private fun isSupported(protocol: Int) = protocol in intArrayOf(1, 58, 6, 17)

    private fun isAddressAllowed(packet: Packet): Allowed? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        lock.read {
            packet.allowed = false
            if (prefs.getBoolean("filter", false)) {
                if (packet.protocol == 17 && !prefs.getBoolean("filter_udp", false)) {
                    packet.allowed = true
                } else if ((packet.uid < 2000 || BuildConfig.PLAY_STORE_RELEASE) && !mapUidKnown.containsKey(packet.uid) && isSupported(packet.protocol)) {
                    packet.allowed = true
                } else if (packet.uid == Process.myUid()) {
                    packet.allowed = true
                } else {
                    var filtered = false
                    val key = IPKey(packet.version, packet.protocol, packet.dport, packet.uid)
                    if (mapUidIPFilters.containsKey(key)) {
                        try {
                            val iaddr = InetAddress.getByName(packet.daddr)
                            val rule = mapUidIPFilters[key]?.get(iaddr)
                            if (rule != null) {
                                if (!rule.isExpired()) {
                                    filtered = true
                                    packet.allowed = !rule.isBlocked()
                                }
                            }
                        } catch (ignored: UnknownHostException) {}
                    }
                    if (!filtered) packet.allowed = mapUidAllowed[packet.uid] == true
                }
            }
        }

        var allowed: Allowed? = null
        if (packet.allowed) {
            val fwd = mapForward[packet.dport]
            if (fwd != null && fwd.ruid != packet.uid) {
                allowed = Allowed(fwd.raddr, fwd.rport)
                packet.data = "> ${fwd.raddr}/${fwd.rport}"
            } else {
                allowed = Allowed()
            }
        }

        if (prefs.getBoolean("log", false) || prefs.getBoolean("log_app", false)) {
            if (packet.protocol != 6 || packet.flags != "") {
                if (packet.uid != Process.myUid()) logPacket(packet)
            }
        }
        return allowed
    }

    private fun accountUsage(usage: Usage) {
        logHandler.account(usage)
    }

    private fun isLockdown(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("lockdown", false)
    }

    @SuppressLint("MissingPermission")
    fun notifyNewApplication(uid: Int, malware: Boolean) {
        if (uid < 0 || uid == Process.myUid()) return
        try {
            val names = Util.getApplicationNames(uid, this)
            if (names.isEmpty()) return
            val name = TextUtils.join(", ", names)
            val pkgs = packageManager.getPackagesForUid(uid) ?: throw PackageManager.NameNotFoundException(uid.toString())
            val internet = Util.hasInternet(uid, this)

            val main = Intent(this, ActivityMain::class.java).apply {
                putExtra(ActivityMain.EXTRA_REFRESH, true)
                putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
            }
            val pi = PendingIntentCompat.getActivity(this, uid, main, PendingIntent.FLAG_UPDATE_CURRENT)

            val tv = TypedValue()
            theme.resolveAttribute(R.attr.colorPrimary, tv, true)
            val builder = NotificationCompat.Builder(this, if (malware) "malware" else "notify")
                .setSmallIcon(R.drawable.ic_security_white_24dp)
                .setContentIntent(pi)
                .setColor(tv.data)
                .setAutoCancel(true)

            if (malware) {
                builder.setContentTitle(name).setContentText(getString(R.string.msg_malware, name))
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    builder.setContentTitle(name).setContentText(getString(R.string.msg_installed_n))
                } else {
                    builder.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.msg_installed, name))
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            }

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val wifi = getSharedPreferences("wifi", Context.MODE_PRIVATE).getBoolean(pkgs[0], prefs.getBoolean("whitelist_wifi", true))
            val other = getSharedPreferences("other", Context.MODE_PRIVATE).getBoolean(pkgs[0], prefs.getBoolean("whitelist_other", true))

            val riWifi = Intent(this, ServiceSinkhole::class.java).apply {
                putExtra(EXTRA_COMMAND, Command.set)
                putExtra(EXTRA_NETWORK, "wifi")
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_PACKAGE, pkgs[0])
                putExtra(EXTRA_BLOCKED, !wifi)
            }
            builder.addAction(NotificationCompat.Action.Builder(if (wifi) R.drawable.wifi_on else R.drawable.wifi_off, getString(if (wifi) R.string.title_allow_wifi else R.string.title_block_wifi), PendingIntentCompat.getService(this, uid, riWifi, PendingIntent.FLAG_UPDATE_CURRENT)).build())

            val riOther = Intent(this, ServiceSinkhole::class.java).apply {
                putExtra(EXTRA_COMMAND, Command.set)
                putExtra(EXTRA_NETWORK, "other")
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_PACKAGE, pkgs[0])
                putExtra(EXTRA_BLOCKED, !other)
            }
            builder.addAction(NotificationCompat.Action.Builder(if (other) R.drawable.other_on else R.drawable.other_off, getString(if (other) R.string.title_allow_other else R.string.title_block_other), PendingIntentCompat.getService(this, uid + 10000, riOther, PendingIntent.FLAG_UPDATE_CURRENT)).build())

            if (internet && Util.canNotify(this)) {
                val notification = builder.build()
                NotificationManagerCompat.from(this).notify(uid, notification!!)
            } else if (!internet) {
                val expanded = NotificationCompat.BigTextStyle(builder)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) expanded.bigText(getString(R.string.msg_installed_n)) else expanded.bigText(getString(R.string.msg_installed, name))
                expanded.setSummaryText(getString(R.string.title_internet))
                if (Util.canNotify(this)) {
                    val notification = expanded.build()
                    NotificationManagerCompat.from(this).notify(uid, notification!!)
                }
            }
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.e(TAG, ex.toString())
        }
    }

    private fun getEnforcingNotification(allowed: Int, blocked: Int, hosts: Int): Notification {
        val main = Intent(this, ActivityMain::class.java)
        val pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder = NotificationCompat.Builder(this, "foreground")
            .setSmallIcon(if (isLockedDown(last_metered)) R.drawable.ic_lock_outline_white_24dp else R.drawable.ic_security_white_24dp)
            .setContentIntent(pi)
            .setColor(tv.data)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setContentTitle(getString(R.string.msg_started))
        } else {
            builder.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.msg_started))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET).priority = NotificationCompat.PRIORITY_MIN
        }

        val a = if (allowed >= 0) allowed.also { last_allowed = it } else last_allowed
        val b = if (blocked >= 0) blocked.also { last_blocked = it } else last_blocked
        val h = if (hosts >= 0) hosts.also { last_hosts = it } else last_hosts

        if (a >= 0 || b >= 0 || h >= 0) {
            val text = if (Util.isPlayStoreInstall(this)) getString(R.string.msg_packages, a, b) else getString(R.string.msg_hosts, a, b, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setContentText(text)
            } else {
                NotificationCompat.BigTextStyle(builder).bigText(getString(R.string.msg_started)).setSummaryText(text)
            }
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun updateEnforcingNotification(allowed: Int, total: Int) {
        val notification = getEnforcingNotification(allowed, total - allowed, mapHostsBlocked.size)
        if (Util.canNotify(this)) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ENFORCING, notification)
        }
    }

    private fun getWaitingNotification(): Notification {
        val main = Intent(this, ActivityMain::class.java)
        val pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder = NotificationCompat.Builder(this, "foreground")
            .setSmallIcon(R.drawable.ic_security_white_24dp)
            .setContentIntent(pi)
            .setColor(tv.data)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setContentTitle(getString(R.string.msg_waiting))
        } else {
            builder.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.msg_waiting))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET).priority = NotificationCompat.PRIORITY_MIN
        }
        return builder.build()
    }

    @SuppressLint("MissingPermission")
    private fun showDisabledNotification() {
        val main = Intent(this, ActivityMain::class.java)
        val pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder = NotificationCompat.Builder(this, "notify")
            .setSmallIcon(R.drawable.ic_error_white_24dp)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.msg_revoked))
            .setContentIntent(pi)
            .setColor(tv.data)
            .setOngoing(false)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        if (Util.canNotify(this)) {
            val notification = NotificationCompat.BigTextStyle(builder).bigText(getString(R.string.msg_revoked)).build()
            NotificationManagerCompat.from(this).notify(NOTIFY_DISABLED, notification!!)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showLockdownNotification() {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
        val pi = PendingIntentCompat.getActivity(this, NOTIFY_LOCKDOWN, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder = NotificationCompat.Builder(this, "notify")
            .setSmallIcon(R.drawable.ic_error_white_24dp)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.msg_always_on_lockdown))
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(tv.data)
            .setOngoing(false)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        if (Util.canNotify(this)) {
            val notification = NotificationCompat.BigTextStyle(builder).bigText(getString(R.string.msg_always_on_lockdown)).build()
            NotificationManagerCompat.from(this).notify(NOTIFY_LOCKDOWN, notification!!)
        }    }

    private fun removeLockdownNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIFY_LOCKDOWN)
    }

    @SuppressLint("MissingPermission")
    private fun showErrorNotification(message: String) {
        val main = Intent(this, ActivityMain::class.java)
        val pi = PendingIntentCompat.getActivity(this, 0, main, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder = NotificationCompat.Builder(this, "notify")
            .setSmallIcon(R.drawable.ic_error_white_24dp)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.msg_error, message))
            .setContentIntent(pi)
            .setColor(tv.data)
            .setOngoing(false)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        if (Util.canNotify(this)) {
            val notification = NotificationCompat.BigTextStyle(builder).bigText(getString(R.string.msg_error, message)).setSummaryText(message).build()
            NotificationManagerCompat.from(this).notify(NOTIFY_ERROR, notification!!)
        }    }

    @SuppressLint("MissingPermission")
    private fun showAccessNotification(uid: Int) {
        val apps = Util.getApplicationNames(uid, this)
        if (apps.isEmpty()) return
        val name = TextUtils.join(", ", apps)
        val main = Intent(this, ActivityMain::class.java).apply { putExtra(ActivityMain.EXTRA_SEARCH, uid.toString()) }
        val pi = PendingIntentCompat.getActivity(this, uid + 10000, main, PendingIntent.FLAG_UPDATE_CURRENT)

        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorOn, tv, true); val colorOn = tv.data
        theme.resolveAttribute(R.attr.colorOff, tv, true); val colorOff = tv.data

        val builder = NotificationCompat.Builder(this, "access")
            .setSmallIcon(R.drawable.ic_cloud_upload_white_24dp)
            .setGroup("AccessAttempt")
            .setContentIntent(pi)
            .setColor(colorOff)
            .setOngoing(false)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setContentTitle(name).setContentText(getString(R.string.msg_access_n))
        } else {
            builder.setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.msg_access, name))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }

        val df = SimpleDateFormat("dd HH:mm", Locale.ROOT)
        val notification = NotificationCompat.InboxStyle(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notification.addLine(getString(R.string.msg_access_n))
        } else {
            val sname = getString(R.string.msg_access, name)
            val pos = sname.indexOf(name)
            val sp = SpannableString(sname).apply { setSpan(StyleSpan(Typeface.BOLD), pos, pos + name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            notification.addLine(sp)
        }

        var since: Long = 0
        packageManager.getPackagesForUid(uid)?.get(0)?.let { pkg -> try { since = packageManager.getPackageInfo(pkg, 0).firstInstallTime } catch (ignored: PackageManager.NameNotFoundException) {} }

        DatabaseHelper.getInstance(this).getAccessUnset(uid, 7, since).use { cursor ->
            val colDAddr = cursor.getColumnIndex("daddr")
            val colTime = cursor.getColumnIndex("time")
            val colAllowed = cursor.getColumnIndex("allowed")
            while (cursor.moveToNext()) {
                val timeStr = df.format(Date(cursor.getLong(colTime)))
                var daddr = cursor.getString(colDAddr)
                if (Util.isNumericAddress(daddr)) try { daddr = InetAddress.getByName(daddr).hostName } catch (ignored: UnknownHostException) {}
                val sb = StringBuilder().append(timeStr).append(' ').append(daddr)
                val allowed = cursor.getInt(colAllowed)
                if (allowed >= 0) {
                    val pos = sb.indexOf(daddr)
                    val sp = SpannableString(sb).apply { setSpan(ForegroundColorSpan(if (allowed > 0) colorOn else colorOff), pos, pos + daddr.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    notification.addLine(sp)
                } else {
                    notification.addLine(sb)
                }
            }
        }
        if (Util.canNotify(this)) {
            val notificationFinal = notification.build()
            NotificationManagerCompat.from(this).notify(uid + 10000, notificationFinal!!)
        }    }

    @SuppressLint("MissingPermission")
    private fun showUpdateNotification(name: String, url: String) {
        val download = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pi = PendingIntentCompat.getActivity(this, 0, download, PendingIntent.FLAG_UPDATE_CURRENT)
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        val builder = NotificationCompat.Builder(this, "notify")
            .setSmallIcon(R.drawable.ic_security_white_24dp)
            .setContentTitle(name)
            .setContentText(getString(R.string.msg_update))
            .setContentIntent(pi)
            .setColor(tv.data)
            .setOngoing(false)
            .setAutoCancel(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }
        if (Util.canNotify(this)) {
            val notification = builder.build()
            NotificationManagerCompat.from(this).notify(NOTIFY_UPDATE, notification!!)
        }
    }

    private fun removeWarningNotifications() {
        val nmc = NotificationManagerCompat.from(this)
        nmc.cancel(NOTIFY_DISABLED)
        nmc.cancel(NOTIFY_AUTOSTART)
        nmc.cancel(NOTIFY_ERROR)
    }

    private inner class Builder : VpnService.Builder() {
        private var activeNetwork: Network? = null
        private var networkInfo: NetworkInfo? = null
        private var mtu: Int = 0
        private val listAddress = ArrayList<String>()
        private val listRoute = ArrayList<String>()
        private val listDns = ArrayList<InetAddress>()
        private val listAllowed = ArrayList<String>()
        private val listDisallowed = ArrayList<String>()

        init {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            activeNetwork = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) null else cm.activeNetwork
            networkInfo = cm.activeNetworkInfo
        }

        override fun setMtu(mtu: Int): VpnService.Builder {
            this.mtu = mtu
            super.setMtu(mtu)
            return this
        }

        override fun addAddress(address: String, prefixLength: Int): Builder {
            listAddress.add("$address/$prefixLength")
            super.addAddress(address, prefixLength)
            return this
        }

        override fun addRoute(address: String, prefixLength: Int): Builder {
            listRoute.add("$address/$prefixLength")
            super.addRoute(address, prefixLength)
            return this
        }

        override fun addRoute(address: InetAddress, prefixLength: Int): Builder {
            listRoute.add("${address.hostAddress}/$prefixLength")
            super.addRoute(address, prefixLength)
            return this
        }

        override fun addDnsServer(address: InetAddress): Builder {
            listDns.add(address)
            super.addDnsServer(address)
            return this
        }

        override fun addAllowedApplication(packageName: String): VpnService.Builder {
            listAllowed.add(packageName)
            return super.addAllowedApplication(packageName)
        }

        override fun addDisallowedApplication(packageName: String): Builder {
            listDisallowed.add(packageName)
            super.addDisallowedApplication(packageName)
            return this
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Builder) return false
            if (activeNetwork != other.activeNetwork) return false
            if (networkInfo?.type != other.networkInfo?.type) return false
            if (mtu != other.mtu) return false
            if (listAddress.size != other.listAddress.size || !listAddress.containsAll(other.listAddress)) return false
            if (listRoute.size != other.listRoute.size || !listRoute.containsAll(other.listRoute)) return false
            if (listDns.size != other.listDns.size || !listDns.containsAll(other.listDns)) return false
            if (listAllowed.size != other.listAllowed.size || !listAllowed.containsAll(other.listAllowed)) return false
            if (listDisallowed.size != other.listDisallowed.size || !listDisallowed.containsAll(other.listDisallowed)) return false
            return true
        }

        override fun hashCode(): Int {
            return Objects.hash(activeNetwork, networkInfo?.type, mtu, listAddress, listRoute, listDns, listAllowed, listDisallowed)
        }
    }

    private inner class IPKey(val version: Int, val protocol: Int, dport: Int, val uid: Int) {
        val dport: Int = if (protocol == 6 || protocol == 17) dport else 0

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IPKey) return false
            return version == other.version && protocol == other.protocol && dport == other.dport && uid == other.uid
        }

        override fun hashCode(): Int {
            return (version shl 40) or (protocol shl 32) or (dport shl 16) or uid
        }

        override fun toString() = "v$version p$protocol port=$dport uid=$uid"
    }

    private inner class IPRule(private val key: IPKey, private val name: String, private val block: Boolean, private var time: Long, private var ttl: Long) {
        fun isBlocked() = block
        fun isExpired() = System.currentTimeMillis() > (time + ttl * 2)
        fun updateExpires(time: Long, ttl: Long) { this.time = time; this.ttl = ttl }
        override fun toString() = "$key $name"
    }

    private enum class State { none, waiting, enforcing, stats }
    enum class Command { run, start, reload, stop, stats, set, householding, watchdog }

    companion object {
        private const val TAG = "NetGuard.Service"
        private const val ACTION_HOUSE_HOLDING = "eu.faircode.netguard.HOUSE_HOLDING"
        private const val ACTION_SCREEN_OFF_DELAYED = "eu.faircode.netguard.SCREEN_OFF_DELAYED"
        private const val ACTION_WATCHDOG = "eu.faircode.netguard.WATCHDOG"

        private const val NOTIFY_ENFORCING = 1
        private const val NOTIFY_WAITING = 2
        private const val NOTIFY_DISABLED = 3
        private const val NOTIFY_LOCKDOWN = 4
        private const val NOTIFY_AUTOSTART = 5
        private const val NOTIFY_ERROR = 6
        private const val NOTIFY_TRAFFIC = 7
        private const val NOTIFY_UPDATE = 8
        const val NOTIFY_EXTERNAL = 9
        const val NOTIFY_DOWNLOAD = 10

        const val EXTRA_COMMAND = "Command"
        private const val EXTRA_REASON = "Reason"
        const val EXTRA_NETWORK = "Network"
        const val EXTRA_UID = "UID"
        const val EXTRA_PACKAGE = "Package"
        const val EXTRA_BLOCKED = "Blocked"
        const val EXTRA_INTERACTIVE = "Interactive"
        const val EXTRA_TEMPORARY = "Temporary"

        private const val MSG_STATS_START = 1
        private const val MSG_STATS_STOP = 2
        private const val MSG_STATS_UPDATE = 3
        private const val MSG_PACKET = 4
        private const val MSG_USAGE = 5

        private val jni_lock = Any()
        private var jni_context: Long = 0

        @Volatile
        private var wlInstance: PowerManager.WakeLock? = null

        @JvmStatic
        fun setPcap(enabled: Boolean, context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val record_size = try { prefs.getString("pcap_record_size", "64")?.toInt() ?: 64 } catch (ex: Throwable) { 64 }
            val file_size = try { (prefs.getString("pcap_file_size", "2")?.toInt() ?: 2) * 1024 * 1024 } catch (ex: Throwable) { 2 * 1024 * 1024 }
            val pcap = if (enabled) File(context.getDir("data", Context.MODE_PRIVATE), "netguard.pcap") else null
            jni_pcap(pcap?.absolutePath, record_size, file_size)
        }

        @JvmStatic
        private external fun jni_pcap(name: String?, record_size: Int, file_size: Int)

        @Synchronized
        private fun getLock(context: Context): PowerManager.WakeLock {
            return wlInstance ?: (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${context.getString(R.string.app_name)} wakelock")
                .apply { setReferenceCounted(true) }
                .also { wlInstance = it }
        }

        @Synchronized
        private fun releaseLock(context: Context) {
            wlInstance?.let {
                while (it.isHeld) it.release()
                wlInstance = null
            }
        }

        @JvmStatic
        fun getDns(context: Context): List<InetAddress> {
            val listDns = ArrayList<InetAddress>()
            val sysDns = Util.getDefaultDNS(context)
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val ip6 = prefs.getBoolean("ip6", true)
            val filter = prefs.getBoolean("filter", false)
            val vpnDns1 = prefs.getString("dns", null)
            val vpnDns2 = prefs.getString("dns2", null)

            vpnDns1?.let { try { val dns = InetAddress.getByName(it); if (!(dns.isLoopbackAddress || dns.isAnyLocalAddress) && (ip6 || dns is Inet4Address)) listDns.add(dns) } catch (ignored: Throwable) {} }
            vpnDns2?.let { try { val dns = InetAddress.getByName(it); if (!(dns.isLoopbackAddress || dns.isAnyLocalAddress) && (ip6 || dns is Inet4Address)) listDns.add(dns) } catch (ignored: Throwable) {} }

            if (listDns.size < 2) {
                sysDns.forEach { try { val ddns = InetAddress.getByName(it); if (!listDns.contains(ddns) && !(ddns.isLoopbackAddress || ddns.isAnyLocalAddress) && (ip6 || ddns is Inet4Address)) listDns.add(ddns) } catch (ignored: Throwable) {} }
            }

            val lan = prefs.getBoolean("lan", false)
            val use_hosts = prefs.getBoolean("use_hosts", false)
            if (lan && use_hosts && filter) {
                try {
                    val subnets = arrayOf(Pair(InetAddress.getByName("10.0.0.0"), 8), Pair(InetAddress.getByName("172.16.0.0"), 12), Pair(InetAddress.getByName("192.168.0.0"), 16))
                    for (subnet in subnets) {
                        val hostAddress = subnet.first
                        val host = BigInteger(1, hostAddress.address)
                        val mask = BigInteger.valueOf(-1).shiftLeft(hostAddress.address.size * 8 - subnet.second)
                        ArrayList(listDns).forEach { dns ->
                            if (hostAddress.address.size == dns.address.size) {
                                val ip = BigInteger(1, dns.address)
                                if (host.and(mask) == ip.and(mask)) listDns.remove(dns)
                            }
                        }
                    }
                } catch (ignored: Throwable) {}
            }

            if (listDns.isEmpty()) {
                try {
                    listDns.add(InetAddress.getByName("8.8.8.8"))
                    listDns.add(InetAddress.getByName("8.8.4.4"))
                    if (ip6) {
                        listDns.add(InetAddress.getByName("2001:4860:4860::8888"))
                        listDns.add(InetAddress.getByName("2001:4860:4860::8844"))
                    }
                } catch (ignored: Throwable) {}
            }
            return listDns
        }

        @JvmStatic
        fun run(reason: String, context: Context) {
            val intent = Intent(context, ServiceSinkhole::class.java).apply { putExtra(EXTRA_COMMAND, Command.run); putExtra(EXTRA_REASON, reason) }
            startServiceInternal(context, intent)
        }

        @JvmStatic
        fun start(reason: String, context: Context) {
            val intent = Intent(context, ServiceSinkhole::class.java).apply { putExtra(EXTRA_COMMAND, Command.start); putExtra(EXTRA_REASON, reason) }
            startServiceInternal(context, intent)
        }

        @JvmStatic
        fun reload(reason: String, context: Context, interactive: Boolean) {
            if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("enabled", false)) {
                val intent = Intent(context, ServiceSinkhole::class.java).apply { putExtra(EXTRA_COMMAND, Command.reload); putExtra(EXTRA_REASON, reason); putExtra(EXTRA_INTERACTIVE, interactive) }
                startServiceInternal(context, intent)
            }
        }

        @JvmStatic
        fun stop(reason: String, context: Context, vpnonly: Boolean) {
            val intent = Intent(context, ServiceSinkhole::class.java).apply { putExtra(EXTRA_COMMAND, Command.stop); putExtra(EXTRA_REASON, reason); putExtra(EXTRA_TEMPORARY, vpnonly) }
            startServiceInternal(context, intent)
        }

        @JvmStatic
        fun reloadStats(reason: String, context: Context) {
            val intent = Intent(context, ServiceSinkhole::class.java).apply { putExtra(EXTRA_COMMAND, Command.stats); putExtra(EXTRA_REASON, reason) }
            startServiceInternal(context, intent)
        }

        private fun startServiceInternal(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (ex: Throwable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ex is ForegroundServiceStartNotAllowedException) {
                    try { context.startService(intent) } catch (ignored: Throwable) {}
                }
            }
        }
    }
}
