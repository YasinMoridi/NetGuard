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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.preference.PreferenceManager
import org.xmlpull.v1.XmlPullParser
import java.text.Collator
import java.util.Locale

class Rule private constructor(dh: DatabaseHelper, info: PackageInfo, context: Context) {
    @JvmField
    var uid: Int = 0
    @JvmField
    var packageName: String? = null
    @JvmField
    var icon: Int = 0
    @JvmField
    var name: String? = null
    @JvmField
    var version: String? = null
    @JvmField
    var system: Boolean = false
    @JvmField
    var internet: Boolean = false
    @JvmField
    var enabled: Boolean = false
    @JvmField
    var pkg: Boolean = true

    @JvmField
    var wifi_default: Boolean = false
    @JvmField
    var other_default: Boolean = false
    @JvmField
    var screen_wifi_default: Boolean = false
    @JvmField
    var screen_other_default: Boolean = false
    @JvmField
    var roaming_default: Boolean = false

    @JvmField
    var wifi_blocked: Boolean = false
    @JvmField
    var other_blocked: Boolean = false
    @JvmField
    var screen_wifi: Boolean = false
    @JvmField
    var screen_other: Boolean = false
    @JvmField
    var roaming: Boolean = false
    @JvmField
    var lockdown: Boolean = false

    @JvmField
    var apply: Boolean = true
    @JvmField
    var notify: Boolean = true

    @JvmField
    var relateduids: Boolean = false
    @JvmField
    var related: Array<String>? = null

    @JvmField
    var hosts: Long = 0
    @JvmField
    var changed: Boolean = false

    @JvmField
    var expanded: Boolean = false

    init {
        uid = info.applicationInfo!!.uid
        packageName = info.packageName
        icon = info.applicationInfo!!.icon
        version = info.versionName
        when (uid) {
            0 -> {
                name = context.getString(R.string.title_root)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1013 -> {
                name = context.getString(R.string.title_mediaserver)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1020 -> {
                name = "MulticastDNSResponder"
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1021 -> {
                name = context.getString(R.string.title_gpsdaemon)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            1051 -> {
                name = context.getString(R.string.title_dnsdaemon)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            9999 -> {
                name = context.getString(R.string.title_nobody)
                system = true
                internet = true
                enabled = true
                pkg = false
            }
            else -> {
                dh.getApp(this.packageName).use { cursor ->
                    if (cursor.moveToNext()) {
                        name = cursor.getString(cursor.getColumnIndexOrThrow("label"))
                        system = cursor.getInt(cursor.getColumnIndexOrThrow("system")) > 0
                        internet = cursor.getInt(cursor.getColumnIndexOrThrow("internet")) > 0
                        enabled = cursor.getInt(cursor.getColumnIndexOrThrow("enabled")) > 0
                    } else {
                        name = getLabel(info, context)
                        system = isSystem(info.packageName, context)
                        internet = hasInternet(info.packageName, context)
                        enabled = isEnabled(info, context)
                        dh.addApp(this.packageName, name, system, internet, enabled)
                    }
                }
            }
        }
    }

    private fun updateChanged(default_wifi: Boolean, default_other: Boolean, default_roaming: Boolean) {
        changed = (wifi_blocked != default_wifi ||
                (other_blocked != default_other) ||
                (wifi_blocked && screen_wifi != screen_wifi_default) ||
                (other_blocked && screen_other != screen_other_default) ||
                ((!other_blocked || screen_other) && roaming != default_roaming) ||
                hosts > 0 || lockdown || !apply)
    }

    fun updateChanged(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val screen_on = prefs.getBoolean("screen_on", false)
        val default_wifi = prefs.getBoolean("whitelist_wifi", true) && screen_on
        val default_other = prefs.getBoolean("whitelist_other", true) && screen_on
        val default_roaming = prefs.getBoolean("whitelist_roaming", true)
        updateChanged(default_wifi, default_other, default_roaming)
    }

    override fun toString(): String {
        return name ?: ""
    }

    companion object {
        private const val TAG = "NetGuard.Rule"

        private var cachePackageInfo: MutableList<PackageInfo>? = null
        private val cacheLabel = mutableMapOf<PackageInfo, String>()
        private val cacheSystem = mutableMapOf<String, Boolean>()
        private val cacheInternet = mutableMapOf<String, Boolean>()
        private val cacheEnabled = mutableMapOf<PackageInfo, Boolean>()

        private fun getPackages(context: Context): MutableList<PackageInfo> {
            if (cachePackageInfo == null) {
                val pm = context.packageManager
                cachePackageInfo = pm.getInstalledPackages(0).toMutableList()
            }
            return cachePackageInfo!!.toMutableList()
        }

        private fun getLabel(info: PackageInfo, context: Context): String {
            return cacheLabel.getOrPut(info) {
                val pm = context.packageManager
                info.applicationInfo?.loadLabel(pm)?.toString() ?: info.packageName
            }
        }

        private fun isSystem(packageName: String, context: Context): Boolean {
            return cacheSystem.getOrPut(packageName) {
                Util.isSystem(packageName, context)
            }
        }

        private fun hasInternet(packageName: String, context: Context): Boolean {
            return cacheInternet.getOrPut(packageName) {
                Util.hasInternet(packageName, context)
            }
        }

        private fun isEnabled(info: PackageInfo, context: Context): Boolean {
            return cacheEnabled.getOrPut(info) {
                Util.isEnabled(info, context)
            }
        }

        @JvmStatic
        fun clearCache(context: Context) {
            Log.i(TAG, "Clearing cache")
            synchronized(context.applicationContext) {
                cachePackageInfo = null
                cacheLabel.clear()
                cacheSystem.clear()
                cacheInternet.clear()
                cacheEnabled.clear()
            }
            val dh = DatabaseHelper.getInstance(context)
            dh.clearApps()
        }

        @JvmStatic
        fun getRules(all: Boolean, context: Context): List<Rule> {
            synchronized(context.applicationContext) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val wifi = context.getSharedPreferences("wifi", Context.MODE_PRIVATE)
                val other = context.getSharedPreferences("other", Context.MODE_PRIVATE)
                val screen_wifi = context.getSharedPreferences("screen_wifi", Context.MODE_PRIVATE)
                val screen_other = context.getSharedPreferences("screen_other", Context.MODE_PRIVATE)
                val roaming = context.getSharedPreferences("roaming", Context.MODE_PRIVATE)
                val lockdown = context.getSharedPreferences("lockdown", Context.MODE_PRIVATE)
                val apply = context.getSharedPreferences("apply", Context.MODE_PRIVATE)
                val notify = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

                val default_wifi = prefs.getBoolean("whitelist_wifi", true)
                val default_other = prefs.getBoolean("whitelist_other", true)
                var default_screen_wifi = prefs.getBoolean("screen_wifi", false)
                var default_screen_other = prefs.getBoolean("screen_other", false)
                val default_roaming = prefs.getBoolean("whitelist_roaming", true)

                val manage_system = prefs.getBoolean("manage_system", false)
                val screen_on = prefs.getBoolean("screen_on", true)
                val show_user = prefs.getBoolean("show_user", true)
                val show_system = prefs.getBoolean("show_system", false)
                val show_nointernet = prefs.getBoolean("show_nointernet", true)
                val show_disabled = prefs.getBoolean("show_disabled", true)

                default_screen_wifi = default_screen_wifi && screen_on
                default_screen_other = default_screen_other && screen_on

                val pre_wifi_blocked = mutableMapOf<String, Boolean>()
                val pre_other_blocked = mutableMapOf<String, Boolean>()
                val pre_roaming = mutableMapOf<String, Boolean>()
                val pre_related = mutableMapOf<String, Array<String>>()
                val pre_system = mutableMapOf<String, Boolean>()

                try {
                    val xml = context.resources.getXml(R.xml.predefined)
                    var eventType = xml.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            when (xml.name) {
                                "wifi" -> {
                                    val pkg = xml.getAttributeValue(null, "package")
                                    val pblocked = xml.getAttributeBooleanValue(null, "blocked", false)
                                    pre_wifi_blocked[pkg] = pblocked
                                }
                                "other" -> {
                                    val pkg = xml.getAttributeValue(null, "package")
                                    val pblocked = xml.getAttributeBooleanValue(null, "blocked", false)
                                    val proaming = xml.getAttributeBooleanValue(null, "roaming", default_roaming)
                                    pre_other_blocked[pkg] = pblocked
                                    pre_roaming[pkg] = proaming
                                }
                                "relation" -> {
                                    val pkg = xml.getAttributeValue(null, "package")
                                    val rel = xml.getAttributeValue(null, "related").split(",").toTypedArray()
                                    pre_related[pkg] = rel
                                }
                                "type" -> {
                                    val pkg = xml.getAttributeValue(null, "package")
                                    val system = xml.getAttributeBooleanValue(null, "system", true)
                                    pre_system[pkg] = system
                                }
                            }
                        }
                        eventType = xml.next()
                    }
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }

                val listRules = mutableListOf<Rule>()
                val listPI = getPackages(context)
                val userId = Process.myUid() / 100000

                // Special packages
                val specialPackages = arrayOf("root", "android.media", "android.multicast", "android.gps", "android.dns", "nobody")
                val specialUids = intArrayOf(0, 1013, 1020, 1021, 1051, 9999)

                for (i in specialPackages.indices) {
                    val info = PackageInfo().apply {
                        packageName = specialPackages[i]
                        versionCode = Build.VERSION.SDK_INT
                        versionName = Build.VERSION.RELEASE
                        applicationInfo = ApplicationInfo().apply {
                            uid = if (specialUids[i] == 0 || specialUids[i] == 9999) specialUids[i] else specialUids[i] + userId * 100000
                            icon = 0
                        }
                    }
                    listPI.add(info)
                }

                val dh = DatabaseHelper.getInstance(context)
                for (info in listPI) {
                    try {
                        if (info.applicationInfo?.uid == Process.myUid()) continue

                        val rule = Rule(dh, info, context)
                        if (pre_system.containsKey(info.packageName)) {
                            rule.system = pre_system[info.packageName] == true
                        }
                        if (info.applicationInfo?.uid == Process.myUid()) {
                            rule.system = true
                        }

                        if (all || ((if (rule.system) show_system else show_user) &&
                                    (show_nointernet || rule.internet) &&
                                    (show_disabled || rule.enabled))) {

                            rule.wifi_default = pre_wifi_blocked[info.packageName] ?: default_wifi
                            rule.other_default = pre_other_blocked[info.packageName] ?: default_other
                            rule.screen_wifi_default = default_screen_wifi
                            rule.screen_other_default = default_screen_other
                            rule.roaming_default = pre_roaming[info.packageName] ?: default_roaming

                            rule.wifi_blocked = (!(rule.system && !manage_system) && wifi.getBoolean(info.packageName, rule.wifi_default))
                            rule.other_blocked = (!(rule.system && !manage_system) && other.getBoolean(info.packageName, rule.other_default))
                            rule.screen_wifi = screen_wifi.getBoolean(info.packageName, rule.screen_wifi_default) && screen_on
                            rule.screen_other = screen_other.getBoolean(info.packageName, rule.screen_other_default) && screen_on
                            rule.roaming = roaming.getBoolean(info.packageName, rule.roaming_default)
                            rule.lockdown = lockdown.getBoolean(info.packageName, false)

                            rule.apply = apply.getBoolean(info.packageName, true)
                            rule.notify = notify.getBoolean(info.packageName, true)

                            val listPkg = mutableListOf<String>()
                            pre_related[info.packageName]?.let { listPkg.addAll(it) }
                            for (pi in listPI) {
                                if (pi.applicationInfo?.uid == rule.uid && pi.packageName != rule.packageName) {
                                    rule.relateduids = true
                                    listPkg.add(pi.packageName)
                                }
                            }
                            rule.related = listPkg.toTypedArray()
                            rule.hosts = dh.getHostCount(rule.uid, true)
                            rule.updateChanged(default_wifi, default_other, default_roaming)
                            listRules.add(rule)
                        }
                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }

                val collator = Collator.getInstance(Locale.getDefault()).apply {
                    strength = Collator.SECONDARY
                }

                val sort = prefs.getString("sort", "name")
                if ("uid" == sort) {
                    listRules.sortWith { r1, r2 ->
                        if (r1.uid < r2.uid) -1
                        else if (r1.uid > r2.uid) 1
                        else {
                            val i = collator.compare(r1.name, r2.name)
                            if (i == 0) (r1.packageName ?: "").compareTo(r2.packageName ?: "") else i
                        }
                    }
                } else {
                    listRules.sortWith { r1, r2 ->
                        if (all || r1.changed == r2.changed) {
                            val i = collator.compare(r1.name, r2.name)
                            if (i == 0) (r1.packageName ?: "").compareTo(r2.packageName ?: "") else i
                        } else {
                            if (r1.changed) -1 else 1
                        }
                    }
                }
                return listRules
            }
        }
    }
}
