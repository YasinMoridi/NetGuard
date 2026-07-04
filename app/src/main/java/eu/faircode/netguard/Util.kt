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

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationErrorReport
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.res.Resources
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.net.ConnectivityManagerCompat
import androidx.preference.PreferenceManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.io.UnsupportedEncodingException
import java.net.HttpURLConnection
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.URL
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.Collections
import kotlin.math.floor
import kotlin.math.log
import kotlin.math.pow

object Util {
    private const val TAG = "NetGuard.Util"

    // Roam like at home
    private val listEU = listOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IS", "IE",
        "IT", "LV", "LI", "LT", "LU", "MT", "NL", "NO", "PL", "PT", "RO", "SK", "SI", "ES", "SE"
    )

    @JvmStatic
    private external fun jni_getprop(name: String): String?

    @JvmStatic
    private external fun is_numeric_address(ip: String): Boolean

    @JvmStatic
    private external fun dump_memory_profile()

    init {
        try {
            System.loadLibrary("netguard")
        } catch (ignored: UnsatisfiedLinkError) {
            System.exit(1)
        }
    }

    @JvmStatic
    fun getSelfVersionName(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "Unknown"
        } catch (ex: PackageManager.NameNotFoundException) {
            ex.toString()
        }
    }

    @JvmStatic
    fun getSelfVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (pInfo.longVersionCode and 0xFFFFFFFFL).toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (ex: PackageManager.NameNotFoundException) {
            -1
        }
    }

    @JvmStatic
    fun isNetworkActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        return cm?.activeNetworkInfo != null
    }

    @JvmStatic
    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            ?: return false

        @Suppress("DEPRECATION")
        var ni = cm.activeNetworkInfo
        if (ni != null && ni.isConnected) return true

        val networks = cm.allNetworks ?: return false

        for (network in networks) {
            ni = cm.getNetworkInfo(network)
            if (ni != null && ni.type != ConnectivityManager.TYPE_VPN && ni.isConnected) return true
        }

        return false
    }

    @JvmStatic
    fun isWifiActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        @Suppress("DEPRECATION")
        val ni = cm?.activeNetworkInfo
        return ni != null && ni.type == ConnectivityManager.TYPE_WIFI
    }

    @JvmStatic
    fun isMeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        return cm != null && ConnectivityManagerCompat.isActiveNetworkMetered(cm)
    }

    @JvmStatic
    fun getWifiSSID(context: Context): String {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
        val ssid = wm?.connectionInfo?.ssid
        return ssid ?: "NULL"
    }

    @JvmStatic
    fun getNetworkType(context: Context): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        @Suppress("DEPRECATION")
        val ni = cm?.activeNetworkInfo
        return ni?.subtype ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
    }

    @JvmStatic
    fun getNetworkGeneration(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        @Suppress("DEPRECATION")
        val ni = cm?.activeNetworkInfo
        return if (ni != null && ni.type == ConnectivityManager.TYPE_MOBILE) getNetworkGeneration(ni.subtype) else null
    }

    @JvmStatic
    fun isRoaming(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
        @Suppress("DEPRECATION")
        val ni = cm?.activeNetworkInfo
        return ni != null && ni.isRoaming
    }

    @JvmStatic
    fun isNational(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            tm != null && tm.simCountryIso != null && tm.simCountryIso == tm.networkCountryIso
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isEU(context: Context): Boolean {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
            tm != null && isEU(tm.simCountryIso) && isEU(tm.networkCountryIso)
        } catch (ignored: Throwable) {
            false
        }
    }

    @JvmStatic
    fun isEU(country: String?): Boolean {
        return country != null && listEU.contains(country.uppercase())
    }

    @JvmStatic
    fun isPrivateDns(context: Context): Boolean {
        var dnsMode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        Log.i(TAG, "Private DNS mode=$dnsMode")
        if (dnsMode == null) dnsMode = "off"
        return "off" != dnsMode
    }

    @JvmStatic
    fun getPrivateDnsSpecifier(context: Context): String? {
        val dnsMode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
        return if ("hostname" == dnsMode) Settings.Global.getString(
            context.contentResolver,
            "private_dns_specifier"
        ) else null
    }

    @JvmStatic
    fun getNetworkGeneration(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
            TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
            else -> "?G"
        }
    }

    @JvmStatic
    fun hasPhoneStatePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED else true
    }

    @JvmStatic
    fun getDefaultDNS(context: Context): List<String> {
        val listDns = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
            val an = cm?.activeNetwork
            if (an != null) {
                val lp = cm.getLinkProperties(an)
                if (lp != null) {
                    val dns = lp.dnsServers
                    for (d in dns) {
                        Log.i(TAG, "DNS from LP: " + d.hostAddress)
                        val address = d.hostAddress
                        if (address != null) {
                            listDns.add(address.split("%".toRegex())[0])
                        }
                    }
                }
            }
        } else {
            val dns1 = jni_getprop("net.dns1")
            val dns2 = jni_getprop("net.dns2")
            if (dns1 != null) listDns.add(dns1.split("%".toRegex())[0])
            if (dns2 != null) listDns.add(dns2.split("%".toRegex())[0])
        }
        return listDns
    }

    @JvmStatic
    fun isNumericAddress(ip: String): Boolean {
        return is_numeric_address(ip)
    }

    @JvmStatic
    fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT_WATCH) pm != null && pm.isScreenOn else pm != null && pm.isInteractive
    }

    @JvmStatic
    fun isPackageInstalled(packageName: String?, context: Context): Boolean {
        if (packageName == null) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (ignored: PackageManager.NameNotFoundException) {
            false
        }
    }

    @JvmStatic
    fun isSystem(uid: Int, context: Context): Boolean {
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid)
        if (pkgs != null) for (pkg in pkgs) if (isSystem(pkg, context)) return true
        return false
    }

    @JvmStatic
    fun isSystem(packageName: String?, context: Context): Boolean {
        if (packageName == null) return false
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            val appInfo = info.applicationInfo
            appInfo != null && (appInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        } catch (ignore: PackageManager.NameNotFoundException) {
            false
        }
    }

    @JvmStatic
    fun hasInternet(packageName: String?, context: Context): Boolean {
        if (packageName == null) return false
        val pm = context.packageManager
        return pm.checkPermission("android.permission.INTERNET", packageName) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun hasInternet(uid: Int, context: Context): Boolean {
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid)
        if (pkgs != null) for (pkg in pkgs) if (hasInternet(pkg, context)) return true
        return false
    }

    @JvmStatic
    fun isEnabled(info: PackageInfo, context: Context): Boolean {
        var setting: Int
        try {
            val pm = context.packageManager
            setting = pm.getApplicationEnabledSetting(info.packageName)
        } catch (ex: IllegalArgumentException) {
            setting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            Log.w(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        return if (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            val appInfo = info.applicationInfo
            appInfo != null && appInfo.enabled
        } else {
            setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
    }

    @JvmStatic
    fun getApplicationNames(uid: Int, context: Context): List<String> {
        val listResult = mutableListOf<String>()
        if (uid == 0) listResult.add(context.getString(R.string.title_root)) else if (uid == 1013) listResult.add(
            context.getString(R.string.title_mediaserver)
        ) else if (uid == 9999) listResult.add(context.getString(R.string.title_nobody)) else {
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid)
            if (pkgs != null) for (pkg in pkgs) try {
                val info = pm.getApplicationInfo(pkg, 0)
                val name = pm.getApplicationLabel(info).toString()
                if (!TextUtils.isEmpty(name)) listResult.add(name)
            } catch (ignored: PackageManager.NameNotFoundException) {
            }
            Collections.sort(listResult)
        }
        return listResult
    }

    @JvmStatic
    fun canFilter(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true

        // https://android-review.googlesource.com/#/c/206710/1/untrusted_app.te
        val tcp = File("/proc/net/tcp")
        val tcp6 = File("/proc/net/tcp6")
        try {
            if (tcp.exists() && tcp.canRead()) return true
        } catch (ignored: SecurityException) {
        }
        return try {
            tcp6.exists() && tcp6.canRead()
        } catch (ignored: SecurityException) {
            false
        }
    }

    @JvmStatic
    fun isDebuggable(context: Context): Boolean {
        val appInfo = context.applicationContext.applicationInfo
        return appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }

    @JvmStatic
    fun isPlayStoreInstall(context: Context): Boolean {
        if (BuildConfig.PLAY_STORE_RELEASE) return true
        return try {
            @Suppress("DEPRECATION")
            "com.android.vending" == context.packageManager.getInstallerPackageName(context.packageName)
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            false
        }
    }

    @JvmStatic
    fun hasXposed(context: Context): Boolean {
        if (true || !isPlayStoreInstall(context)) return false
        for (ste in Thread.currentThread().stackTrace) if (ste.className.startsWith("de.robv.android.xposed")) return true
        return false
    }

    @JvmStatic
    fun ownFault(context: Context, ex: Throwable): Boolean {
        var e = ex
        if (e is OutOfMemoryError) return false
        if (e.cause != null) e = e.cause!!
        for (ste in e.stackTrace) if (ste.className.startsWith(context.packageName)) return true
        return false
    }

    @JvmStatic
    fun getFingerprint(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            val signatures = info.signatures
            if (signatures != null && signatures.isNotEmpty()) {
                val cert = signatures[0].toByteArray()
                val digest = MessageDigest.getInstance("SHA1")
                val bytes = digest.digest(cert)
                val sb = StringBuilder()
                for (b in bytes) sb.append(Integer.toString(b.toInt() and 0xff, 16).lowercase())
                sb.toString()
            } else {
                null
            }
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            null
        }
    }

    @JvmStatic
    fun hasValidFingerprint(context: Context): Boolean {
        val calculated = getFingerprint(context)
        val expected = context.getString(R.string.fingerprint)
        return calculated != null && calculated == expected
    }

    @JvmStatic
    fun setTheme(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val dark = prefs.getBoolean("dark_theme", false)
        val theme = prefs.getString("theme", "teal")
        if (theme == "teal") context.setTheme(if (dark) R.style.AppThemeTealDark else R.style.AppThemeTeal) else if (theme == "blue") context.setTheme(
            if (dark) R.style.AppThemeBlueDark else R.style.AppThemeBlue
        ) else if (theme == "purple") context.setTheme(if (dark) R.style.AppThemePurpleDark else R.style.AppThemePurple) else if (theme == "amber") context.setTheme(
            if (dark) R.style.AppThemeAmberDark else R.style.AppThemeAmber
        ) else if (theme == "orange") context.setTheme(if (dark) R.style.AppThemeOrangeDark else R.style.AppThemeOrange) else if (theme == "green") context.setTheme(
            if (dark) R.style.AppThemeGreenDark else R.style.AppThemeGreen
        )
        if (context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) setTaskColor(
            context
        )
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun setTaskColor(context: Context) {
        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorPrimary, tv, true)
        @Suppress("DEPRECATION")
        (context as Activity).setTaskDescription(ActivityManager.TaskDescription(null, null, tv.data))
    }

    @JvmStatic
    fun dips2pixels(dips: Int, context: Context): Int {
        return Math.round(dips * context.resources.displayMetrics.density + 0.5f)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    @JvmStatic
    fun decodeSampledBitmapFromResource(
        resources: Resources?, resourceId: Int, reqWidth: Int, reqHeight: Int
    ): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeResource(resources, resourceId, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeResource(resources, resourceId, options)
    }

    @JvmStatic
    fun getProtocolName(protocol: Int, version: Int, brief: Boolean): String {
        // https://en.wikipedia.org/wiki/List_of_IP_protocol_numbers
        var p: String? = null
        var b: String? = null
        when (protocol) {
            0 -> {
                p = "HOPO"
                b = "H"
            }

            2 -> {
                p = "IGMP"
                b = "G"
            }

            1, 58 -> {
                p = "ICMP"
                b = "I"
            }

            6 -> {
                p = "TCP"
                b = "T"
            }

            17 -> {
                p = "UDP"
                b = "U"
            }

            50 -> {
                p = "ESP"
                b = "E"
            }
        }
        return if (p == null) protocol.toString() + "/" + version else (if (brief) b else p)!! + if (version > 0) version else ""
    }

    fun interface DoubtListener {
        fun onSure()
    }

    @JvmStatic
    fun areYouSure(context: Context, explanation: Int, listener: DoubtListener) {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.sure, null, false)
        val tvExplanation = view.findViewById<TextView>(R.id.tvExplanation)
        tvExplanation.setText(explanation)
        AlertDialog.Builder(context)
            .setView(view)
            .setCancelable(true)
            .setPositiveButton(android.R.string.yes) { _, _ -> listener.onSure() }
            .setNegativeButton(android.R.string.no) { _, _ -> }
            .create().show()
    }

    private val mapIPOrganization = mutableMapOf<String, String?>()

    @JvmStatic
    @Throws(Exception::class)
    fun getOrganization(ip: String): String? {
        synchronized(mapIPOrganization) {
            if (mapIPOrganization.containsKey(ip)) return mapIPOrganization[ip]
        }
        var reader: BufferedReader? = null
        return try {
            val url = URL("https://ipinfo.io/$ip/org")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.readTimeout = 15 * 1000
            connection.connect()
            reader = BufferedReader(InputStreamReader(connection.inputStream))
            var organization = reader.readLine()
            if ("undefined" == organization) organization = null
            synchronized(mapIPOrganization) { mapIPOrganization.put(ip, organization) }
            organization
        } finally {
            reader?.close()
        }
    }

    @JvmStatic
    @Throws(NoSuchAlgorithmException::class, UnsupportedEncodingException::class)
    fun md5(text: String, salt: String): String {
        // MD5
        val bytes = MessageDigest.getInstance("MD5").digest((text + salt).toByteArray(charset("UTF-8")))
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    @JvmStatic
    fun logExtras(intent: Intent?) {
        if (intent != null) logBundle(intent.extras)
    }

    @JvmStatic
    fun logBundle(data: Bundle?) {
        if (data != null) {
            val keys = data.keySet()
            val stringBuilder = StringBuilder()
            for (key in keys) {
                val value = data[key]
                stringBuilder.append(key)
                    .append("=")
                    .append(value)
                    .append(if (value == null) "" else " (" + value.javaClass.simpleName + ")")
                    .append("\r\n")
            }
            Log.d(TAG, stringBuilder.toString())
        }
    }

    @JvmStatic
    fun readString(reader: InputStreamReader): StringBuilder {
        val sb = StringBuilder(2048)
        val read = CharArray(128)
        try {
            var i: Int
            while (reader.read(read).also { i = it } >= 0) sb.append(read, 0, i)
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        return sb
    }

    @JvmStatic
    fun sendCrashReport(ex: Throwable, context: Context) {
        if (!isPlayStoreInstall(context) || isDebuggable(context)) return
        try {
            val report = ApplicationErrorReport()
            report.processName = context.packageName
            report.packageName = report.processName
            report.time = System.currentTimeMillis()
            report.type = ApplicationErrorReport.TYPE_CRASH
            report.systemApp = false
            val crash = ApplicationErrorReport.CrashInfo()
            crash.exceptionClassName = ex.javaClass.simpleName
            crash.exceptionMessage = ex.message
            val writer = StringWriter()
            val printer = PrintWriter(writer)
            ex.printStackTrace(printer)
            crash.stackTrace = writer.toString()
            val stack = ex.stackTrace[0]
            crash.throwClassName = stack.className
            crash.throwFileName = stack.fileName
            crash.throwLineNumber = stack.lineNumber
            crash.throwMethodName = stack.methodName
            report.crashInfo = crash
            val bug = Intent(Intent.ACTION_APP_ERROR)
            bug.putExtra(Intent.EXTRA_BUG_REPORT, report)
            bug.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (bug.resolveActivity(context.packageManager) != null) context.startActivity(bug)
        } catch (exex: Throwable) {
            Log.e(TAG, exex.toString() + "\n" + Log.getStackTraceString(exex))
        }
    }

    @JvmStatic
    fun getGeneralInfo(context: Context): String {
        val sb = StringBuilder()
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        sb.append(String.format("Interactive %B\r\n", isInteractive(context)))
        sb.append(String.format("Connected %B\r\n", isConnected(context)))
        sb.append(String.format("WiFi %B\r\n", isWifiActive(context)))
        sb.append(String.format("Metered %B\r\n", isMeteredNetwork(context)))
        sb.append(String.format("Roaming %B\r\n", isRoaming(context)))
        if (tm.simState == TelephonyManager.SIM_STATE_READY) sb.append(
            String.format(
                "SIM %s/%s/%s\r\n",
                tm.simCountryIso,
                tm.simOperatorName,
                tm.simOperator
            )
        )
        //if (tm.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN)
        try {
            sb.append(
                String.format(
                    "Network %s/%s/%s\r\n",
                    tm.networkCountryIso,
                    tm.networkOperatorName,
                    tm.networkOperator
                )
            )
        } catch (ex: Throwable) {
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) sb.append(
            String.format(
                "Power saving %B\r\n",
                pm.isPowerSaveMode
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sb.append(
            String.format(
                "Battery optimizing %B\r\n",
                batteryOptimizing(context)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) sb.append(
            String.format(
                "Data saving %B\r\n",
                dataSaving(context)
            )
        )
        if (sb.length > 2) sb.setLength(sb.length - 2)
        return sb.toString()
    }

    @JvmStatic
    fun getNetworkInfo(context: Context): String {
        val sb = StringBuilder()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val ani = cm.activeNetworkInfo
        val listNI = mutableListOf<NetworkInfo>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            listNI.addAll(cm.allNetworkInfo)
        } else {
            for (network in cm.allNetworks) {
                @Suppress("DEPRECATION")
                val ni = cm.getNetworkInfo(network)
                if (ni != null) listNI.add(ni)
            }
        }
        for (ni in listNI) {
            sb.append(ni.typeName).append('/').append(ni.subtypeName)
                .append(' ').append(ni.detailedState)
                .append(if (TextUtils.isEmpty(ni.extraInfo)) "" else " " + ni.extraInfo)
                .append(if (ni.type == ConnectivityManager.TYPE_MOBILE) " " + getNetworkGeneration(ni.subtype) else "")
                .append(if (ni.isRoaming) " R" else "")
                .append(if (ani != null && ni.type == ani.type && ni.subtype == ani.subtype) " *" else "")
                .append("\r\n")
        }
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            if (nis != null) while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (ni != null && !ni.isLoopback) {
                    val ias = ni.interfaceAddresses
                    if (ias != null) for (ia in ias) sb.append(ni.name)
                        .append(' ').append(ia.address.hostAddress)
                        .append('/').append(ia.networkPrefixLength.toInt())
                        .append(' ').append(ni.mtu)
                        .append(' ').append(if (ni.isUp) '^' else 'v')
                        .append("\r\n")
                }
            }
        } catch (ex: Throwable) {
            sb.append(ex.toString()).append("\r\n")
        }
        if (sb.length > 2) sb.setLength(sb.length - 2)
        return sb.toString()
    }

    @JvmStatic
    @TargetApi(Build.VERSION_CODES.M)
    fun batteryOptimizing(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.getPackageName())
    }

    @JvmStatic
    @TargetApi(Build.VERSION_CODES.N)
    fun dataSaving(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    @JvmStatic
    fun canNotify(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true else ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    private fun getTrafficLog(context: Context): StringBuilder {
        val sb = StringBuilder()
        try {
            DatabaseHelper.getInstance(context).getLog(true, true, true, true, true).use { cursor ->
                val colTime = cursor.getColumnIndex("time")
                val colVersion = cursor.getColumnIndex("version")
                val colProtocol = cursor.getColumnIndex("protocol")
                val colFlags = cursor.getColumnIndex("flags")
                val colSAddr = cursor.getColumnIndex("saddr")
                val colSPort = cursor.getColumnIndex("sport")
                val colDAddr = cursor.getColumnIndex("daddr")
                val colDPort = cursor.getColumnIndex("dport")
                val colDName = cursor.getColumnIndex("dname")
                val colUid = cursor.getColumnIndex("uid")
                val colData = cursor.getColumnIndex("data")
                val colAllowed = cursor.getColumnIndex("allowed")
                val colConnection = cursor.getColumnIndex("connection")
                val colInteractive = cursor.getColumnIndex("interactive")
                val format = SimpleDateFormat.getDateTimeInstance()
                var count = 0
                while (cursor.moveToNext() && ++count < 250) {
                    sb.append(format.format(cursor.getLong(colTime)))
                    sb.append(" v").append(cursor.getInt(colVersion))
                    sb.append(" p").append(cursor.getInt(colProtocol))
                    sb.append(' ').append(cursor.getString(colFlags))
                    sb.append(' ').append(cursor.getString(colSAddr))
                    sb.append('/').append(cursor.getInt(colSPort))
                    sb.append(" > ").append(cursor.getString(colDAddr))
                    sb.append('/').append(cursor.getString(colDName))
                    sb.append('/').append(cursor.getInt(colDPort))
                    sb.append(" u").append(cursor.getInt(colUid))
                    sb.append(" a").append(cursor.getInt(colAllowed))
                    sb.append(" c").append(cursor.getInt(colConnection))
                    sb.append(" i").append(cursor.getInt(colInteractive))
                    sb.append(' ').append(cursor.getString(colData))
                    sb.append("\r\n")
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
        return sb
    }
}
