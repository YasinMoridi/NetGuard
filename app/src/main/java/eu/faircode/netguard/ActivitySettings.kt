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
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.preference.*
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.util.Log
import android.util.Xml
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils
import androidx.core.content.ContextCompat
import androidx.core.util.PatternsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import org.xmlpull.v1.XmlSerializer
import java.io.*
import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.SAXParserFactory

@Suppress("DEPRECATION")
class ActivitySettings : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var running = false
    private var dialogFilter: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction().replace(android.R.id.content, FragmentSettings()).commit()
        supportActionBar?.setTitle(R.string.menu_settings)
        running = true
    }

    private fun getPreferenceScreen(): PreferenceScreen {
        return (fragmentManager.findFragmentById(android.R.id.content) as PreferenceFragment).preferenceScreen
    }

    @SuppressLint("MissingPermission")
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val screen = getPreferenceScreen()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val catOptions = (screen.findPreference("screen_options") as PreferenceGroup).findPreference("category_options") as PreferenceGroup
        val catNetwork = (screen.findPreference("screen_network_options") as PreferenceGroup).findPreference("category_network_options") as PreferenceGroup
        val catAdvanced = (screen.findPreference("screen_advanced_options") as PreferenceGroup).findPreference("category_advanced_options") as PreferenceGroup
        val catStats = (screen.findPreference("screen_stats") as PreferenceGroup).findPreference("category_stats") as PreferenceGroup
        val catBackup = (screen.findPreference("screen_backup") as PreferenceGroup).findPreference("category_backup") as PreferenceGroup

        // Handle auto enable
        val prefAutoEnable = screen.findPreference("auto_enable")
        prefAutoEnable.title = getString(R.string.setting_auto, prefs.getString("auto_enable", "0"))

        // Handle screen delay
        val prefScreenDelay = screen.findPreference("screen_delay")
        prefScreenDelay.title = getString(R.string.setting_delay, prefs.getString("screen_delay", "0"))

        // Handle theme
        val prefScreenTheme = screen.findPreference("theme")
        val theme = prefs.getString("theme", "teal")
        val themeNames = resources.getStringArray(R.array.themeNames)
        val themeValues = resources.getStringArray(R.array.themeValues)
        for (i in themeNames.indices) {
            if (theme == themeValues[i]) {
                prefScreenTheme.title = getString(R.string.setting_theme, themeNames[i])
                break
            }
        }

        // Wi-Fi home
        val prefWifiHomes = screen.findPreference("wifi_homes") as MultiSelectListPreference
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            catNetwork.removePreference(prefWifiHomes)
        } else {
            val ssids = prefs.getStringSet("wifi_homes", HashSet()) ?: HashSet()
            prefWifiHomes.title = if (ssids.isNotEmpty()) {
                getString(R.string.setting_wifi_home, TextUtils.join(", ", ssids))
            } else {
                getString(R.string.setting_wifi_home, "-")
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val listSSID = ArrayList<CharSequence>()
            try {
                @Suppress("DEPRECATION")
                val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    emptyList()
                } else {
                    wm.configuredNetworks
                }
                if (configs != null) {
                    for (config in configs) {
                        listSSID.add(config.SSID ?: "NULL")
                    }
                }
            } catch (ignored: SecurityException) {
            }
            for (ssid in ssids) {
                if (!listSSID.contains(ssid)) {
                    listSSID.add(ssid)
                }
            }
            prefWifiHomes.entries = listSSID.toTypedArray()
            prefWifiHomes.entryValues = listSSID.toTypedArray()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefHandover = screen.findPreference("handover") as TwoStatePreference
            catAdvanced.removePreference(prefHandover)
        }

        val prefResetUsage = screen.findPreference("reset_usage")
        prefResetUsage.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            Util.areYouSure(this, R.string.setting_reset_usage) {
                object : AsyncTask<Any?, Any?, Throwable?>() {
                    override fun doInBackground(vararg objects: Any?): Throwable? {
                        return try {
                            DatabaseHelper.getInstance(this@ActivitySettings).resetUsage(-1)
                            null
                        } catch (ex: Throwable) {
                            ex
                        }
                    }

                    override fun onPostExecute(ex: Throwable?) {
                        if (ex == null) {
                            Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                        }
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
            }
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val prefReloadOnconnectivity = screen.findPreference("reload_onconnectivity") as TwoStatePreference
            prefReloadOnconnectivity.isChecked = true
            prefReloadOnconnectivity.isEnabled = false
        }

        // Handle port forwarding
        val prefForwarding = screen.findPreference("forwarding")
        prefForwarding.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivity(Intent(this, ActivityForwarding::class.java))
            true
        }

        val can = Util.canFilter(this)
        val prefLogApp = screen.findPreference("log_app") as TwoStatePreference
        val prefFilter = screen.findPreference("filter") as TwoStatePreference
        prefLogApp.isEnabled = can
        prefFilter.isEnabled = can
        if (!can) {
            prefLogApp.setSummary(R.string.msg_unavailable)
            prefFilter.setSummary(R.string.msg_unavailable)
        }

        // VPN parameters
        screen.findPreference("vpn4").title = getString(R.string.setting_vpn4, prefs.getString("vpn4", "10.1.10.1"))
        screen.findPreference("vpn6").title = getString(R.string.setting_vpn6, prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        val prefDns1 = screen.findPreference("dns") as EditTextPreference
        val prefDns2 = screen.findPreference("dns2") as EditTextPreference
        val prefValidate = screen.findPreference("validate") as EditTextPreference
        val prefTtl = screen.findPreference("ttl") as EditTextPreference
        prefDns1.title = getString(R.string.setting_dns, prefs.getString("dns", "-"))
        prefDns2.title = getString(R.string.setting_dns, prefs.getString("dns2", "-"))
        prefValidate.title = getString(R.string.setting_validate, prefs.getString("validate", "www.google.com"))
        prefTtl.title = getString(R.string.setting_ttl, prefs.getString("ttl", "259200"))

        // SOCKS5 parameters
        screen.findPreference("socks5_addr").title = getString(R.string.setting_socks5_addr, prefs.getString("socks5_addr", "-"))
        screen.findPreference("socks5_port").title = getString(R.string.setting_socks5_port, prefs.getString("socks5_port", "-"))
        screen.findPreference("socks5_username").title = getString(R.string.setting_socks5_username, prefs.getString("socks5_username", "-"))
        screen.findPreference("socks5_password").title = getString(R.string.setting_socks5_password, if (TextUtils.isEmpty(prefs.getString("socks5_username", ""))) "-" else "*****")

        // PCAP parameters
        screen.findPreference("pcap_record_size").title = getString(R.string.setting_pcap_record_size, prefs.getString("pcap_record_size", "64"))
        screen.findPreference("pcap_file_size").title = getString(R.string.setting_pcap_file_size, prefs.getString("pcap_file_size", "2"))

        // Watchdog
        screen.findPreference("watchdog").title = getString(R.string.setting_watchdog, prefs.getString("watchdog", "0"))

        // Show resolved
        val prefShowResolved = screen.findPreference("show_resolved")
        if (Util.isPlayStoreInstall(this)) {
            catAdvanced.removePreference(prefShowResolved)
        } else {
            prefShowResolved.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivity(Intent(this, ActivityDns::class.java))
                true
            }
        }

        // Handle stats
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            catStats.removePreference(screen.findPreference("show_top"))
        }
        val prefStatsFrequency = screen.findPreference("stats_frequency") as EditTextPreference
        val prefStatsSamples = screen.findPreference("stats_samples") as EditTextPreference
        prefStatsFrequency.title = getString(R.string.setting_stats_frequency, prefs.getString("stats_frequency", "1000"))
        prefStatsSamples.title = getString(R.string.setting_stats_samples, prefs.getString("stats_samples", "90"))

        // Handle export
        val prefExport = screen.findPreference("export")
        prefExport.isEnabled = intentCreateExport.resolveActivity(packageManager) != null
        prefExport.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivityForResult(intentCreateExport, REQUEST_EXPORT)
            true
        }

        // Handle import
        val prefImport = screen.findPreference("import")
        prefImport.isEnabled = intentOpenExport.resolveActivity(packageManager) != null
        prefImport.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            startActivityForResult(intentOpenExport, REQUEST_IMPORT)
            true
        }

        // Hosts file settings
        val prefBlockDomains = screen.findPreference("use_hosts")
        val prefRcode = screen.findPreference("rcode") as EditTextPreference
        val prefHostsImport = screen.findPreference("hosts_import")
        val prefHostsImportAppend = screen.findPreference("hosts_import_append")
        val prefHostsUrl = screen.findPreference("hosts_url") as EditTextPreference
        val prefHostsDownload = screen.findPreference("hosts_download")

        prefRcode.title = getString(R.string.setting_rcode, prefs.getString("rcode", "3"))

        if (Util.isPlayStoreInstall(this) || !Util.hasValidFingerprint(this)) {
            catOptions.removePreference(screen.findPreference("update_check"))
        }

        if (Util.isPlayStoreInstall(this)) {
            Log.i(TAG, "Play store install")
            catAdvanced.removePreference(prefBlockDomains)
            catAdvanced.removePreference(prefRcode)
            catAdvanced.removePreference(screen.findPreference("forwarding"))
            catBackup.removePreference(prefHostsImport)
            catBackup.removePreference(prefHostsImportAppend)
            catBackup.removePreference(prefHostsUrl)
            catBackup.removePreference(prefHostsDownload)
        } else {
            val lastImport = prefs.getString("hosts_last_import", null)
            val lastDownload = prefs.getString("hosts_last_download", null)
            if (lastImport != null) {
                prefHostsImport.setSummary(getString(R.string.msg_import_last, lastImport))
            }
            if (lastDownload != null) {
                prefHostsDownload.setSummary(getString(R.string.msg_download_last, lastDownload))
            }

            // Handle hosts import
            prefHostsImport.isEnabled = intentOpenHosts.resolveActivity(packageManager) != null
            prefHostsImport.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivityForResult(intentOpenHosts, REQUEST_HOSTS)
                true
            }
            prefHostsImportAppend.isEnabled = prefHostsImport.isEnabled
            prefHostsImportAppend.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                startActivityForResult(intentOpenHosts, REQUEST_HOSTS_APPEND)
                true
            }

            // Handle hosts file download
            prefHostsUrl.summary = prefHostsUrl.text
            prefHostsDownload.onPreferenceClickListener = Preference.OnPreferenceClickListener {
                val tmp = File(filesDir, "hosts.tmp")
                val hosts = File(filesDir, "hosts.txt")

                val prefHostsUrlEt = screen.findPreference("hosts_url") as EditTextPreference
                var hostsUrl = prefHostsUrlEt.text
                if ("https://www.netguard.me/hosts" == hostsUrl) {
                    hostsUrl = BuildConfig.HOSTS_FILE_URI
                }

                try {
                    DownloadTask(this, URL(hostsUrl), tmp, object : DownloadTask.Listener {
                        override fun onCompleted() {
                            if (hosts.exists()) {
                                hosts.delete()
                            }
                            tmp.renameTo(hosts)

                            val last = SimpleDateFormat.getDateTimeInstance().format(Date())
                            prefs.edit().putString("hosts_last_download", last).apply()

                            if (running) {
                                prefHostsDownload.setSummary(getString(R.string.msg_download_last, last))
                                Toast.makeText(this@ActivitySettings, R.string.msg_downloaded, Toast.LENGTH_LONG).show()
                            }

                            ServiceSinkhole.reload("hosts file download", this@ActivitySettings, false)
                        }

                        override fun onCancelled() {
                            if (tmp.exists()) {
                                tmp.delete()
                            }
                        }

                        override fun onException(ex: Throwable) {
                            if (tmp.exists()) {
                                tmp.delete()
                            }

                            if (running) {
                                Toast.makeText(this@ActivitySettings, ex.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                } catch (ex: MalformedURLException) {
                    Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                }
                true
            }
        }

        // Development
        if (!Util.isDebuggable(this)) {
            screen.removePreference(screen.findPreference("screen_development"))
        }

        // Handle technical info
        val listener = Preference.OnPreferenceClickListener {
            updateTechnicalInfo()
            true
        }

        // Technical info
        val prefTechnicalInfo = screen.findPreference("technical_info")
        val prefTechnicalNetwork = screen.findPreference("technical_network")
        prefTechnicalInfo.isEnabled = INTENT_VPN_SETTINGS.resolveActivity(packageManager) != null
        prefTechnicalInfo.intent = INTENT_VPN_SETTINGS
        prefTechnicalInfo.onPreferenceClickListener = listener
        prefTechnicalNetwork.onPreferenceClickListener = listener
        updateTechnicalInfo()

        markPro(screen.findPreference("theme"), ActivityPro.SKU_THEME)
        markPro(screen.findPreference("install"), ActivityPro.SKU_NOTIFY)
        markPro(screen.findPreference("show_stats"), ActivityPro.SKU_SPEED)
    }

    override fun onResume() {
        super.onResume()
        checkPermissions(null)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

        val ifInteractive = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, interactiveStateReceiver, ifInteractive, ContextCompat.RECEIVER_NOT_EXPORTED)

        val ifConnectivity = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        ContextCompat.registerReceiver(this, connectivityChangedReceiver, ifConnectivity, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(interactiveStateReceiver)
        unregisterReceiver(connectivityChangedReceiver)
    }

    override fun onDestroy() {
        running = false
        dialogFilter?.dismiss()
        dialogFilter = null
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                Log.i(TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String?) {
        // Pro features
        if ("theme" == name) {
            if ("teal" != prefs.getString(name, "teal") && !IAB.isPurchased(ActivityPro.SKU_THEME, this)) {
                prefs.edit().putString(name, "teal").apply()
                (getPreferenceScreen().findPreference(name) as ListPreference).value = "teal"
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
        } else if ("install" == name) {
            if (prefs.getBoolean(name, false) && !IAB.isPurchased(ActivityPro.SKU_NOTIFY, this)) {
                prefs.edit().putBoolean(name, false).apply()
                (getPreferenceScreen().findPreference(name) as TwoStatePreference).isChecked = false
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
        } else if ("show_stats" == name) {
            if (prefs.getBoolean(name, false) && !IAB.isPurchased(ActivityPro.SKU_SPEED, this)) {
                prefs.edit().putBoolean(name, false).apply()
                startActivity(Intent(this, ActivityPro::class.java))
                return
            }
            (getPreferenceScreen().findPreference(name) as TwoStatePreference).isChecked = prefs.getBoolean(name, false)
        }

        val value = prefs.all[name]
        if (value is String && "" == value) {
            prefs.edit().remove(name).apply()
        }

        // Dependencies
        when (name) {
            "screen_on", "whitelist_wifi", "screen_wifi", "whitelist_other", "screen_other", "whitelist_roaming" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "auto_enable" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_auto, prefs.getString(name, "0"))
            }
            "screen_delay" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_delay, prefs.getString(name, "0"))
            }
            "theme", "dark_theme" -> {
                recreate()
            }
            "subnet", "tethering", "lan", "ip6" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "wifi_homes" -> {
                val prefWifiHomes = getPreferenceScreen().findPreference(name) as MultiSelectListPreference
                val ssid = prefs.getStringSet(name, HashSet()) ?: HashSet()
                prefWifiHomes.title = if (ssid.isNotEmpty()) {
                    getString(R.string.setting_wifi_home, TextUtils.join(", ", ssid))
                } else {
                    getString(R.string.setting_wifi_home, "-")
                }
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "use_metered", "unmetered_2g", "unmetered_3g", "unmetered_4g", "national_roaming", "eu_roaming" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "disable_on_call" -> {
                if (prefs.getBoolean(name, false)) {
                    if (checkPermissions(name)) {
                        ServiceSinkhole.reload("changed $name", this, false)
                    }
                } else {
                    ServiceSinkhole.reload("changed $name", this, false)
                }
            }
            "lockdown_wifi", "lockdown_other" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "manage_system" -> {
                val manage = prefs.getBoolean(name, false)
                if (!manage) {
                    prefs.edit().putBoolean("show_user", true).apply()
                }
                prefs.edit().putBoolean("show_system", manage).apply()
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "log_app" -> {
                val ruleset = Intent(ActivityMain.ACTION_RULES_CHANGED)
                LocalBroadcastManager.getInstance(this).sendBroadcast(ruleset)
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "notify_access" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "filter" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && prefs.getBoolean(name, false)) {
                    val inflater = LayoutInflater.from(this)
                    val view = inflater.inflate(R.layout.filter, null, false)
                    dialogFilter = AlertDialog.Builder(this)
                        .setView(view)
                        .setCancelable(false)
                        .setPositiveButton(android.R.string.yes) { _, _ -> }
                        .setOnDismissListener { dialogFilter = null }
                        .create()
                    dialogFilter?.show()
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP && !prefs.getBoolean(name, false)) {
                    prefs.edit().putBoolean(name, true).apply()
                    Toast.makeText(this, R.string.msg_filter4, Toast.LENGTH_SHORT).show()
                }
                (getPreferenceScreen().findPreference(name) as TwoStatePreference).isChecked = prefs.getBoolean(name, false)
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "use_hosts" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "vpn4" -> {
                val vpn4 = prefs.getString(name, null)
                try {
                    checkAddress(vpn4, false)
                    prefs.edit().putString(name, vpn4?.trim()).apply()
                } catch (ex: Throwable) {
                    prefs.edit().remove(name).apply()
                    (getPreferenceScreen().findPreference(name) as EditTextPreference).text = null
                    if (!TextUtils.isEmpty(vpn4)) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_vpn4, prefs.getString(name, "10.1.10.1"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "vpn6" -> {
                val vpn6 = prefs.getString(name, null)
                try {
                    checkAddress(vpn6, false)
                    prefs.edit().putString(name, vpn6?.trim()).apply()
                } catch (ex: Throwable) {
                    prefs.edit().remove(name).apply()
                    (getPreferenceScreen().findPreference(name) as EditTextPreference).text = null
                    if (!TextUtils.isEmpty(vpn6)) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_vpn6, prefs.getString(name, "fd00:1:fd00:1:fd00:1:fd00:1:fd00:1"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "dns", "dns2" -> {
                val dns = prefs.getString(name, null)
                try {
                    checkAddress(dns, true)
                    prefs.edit().putString(name, dns?.trim()).apply()
                } catch (ex: Throwable) {
                    prefs.edit().remove(name).apply()
                    (getPreferenceScreen().findPreference(name) as EditTextPreference).text = null
                    if (!TextUtils.isEmpty(dns)) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_dns, prefs.getString(name, "-"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "validate" -> {
                val host = prefs.getString(name, "www.google.com")
                try {
                    checkDomain(host)
                    prefs.edit().putString(name, host?.trim()).apply()
                } catch (ex: Throwable) {
                    prefs.edit().remove(name).apply()
                    (getPreferenceScreen().findPreference(name) as EditTextPreference).text = null
                    if (!TextUtils.isEmpty(host)) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_validate, prefs.getString(name, "www.google.com"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "ttl" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_ttl, prefs.getString(name, "259200"))
            }
            "rcode" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_rcode, prefs.getString(name, "3"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "socks5_enabled" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "socks5_addr" -> {
                val socks5Addr = prefs.getString(name, null)
                try {
                    if (!socks5Addr.isNullOrEmpty() && !Util.isNumericAddress(socks5Addr)) {
                        throw IllegalArgumentException("Bad address")
                    }
                } catch (ex: Throwable) {
                    prefs.edit().remove(name).apply()
                    (getPreferenceScreen().findPreference(name) as EditTextPreference).text = null
                    if (!TextUtils.isEmpty(socks5Addr)) {
                        Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_socks5_addr, prefs.getString(name, "-"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "socks5_port", "socks5_username" -> {
                getPreferenceScreen().findPreference(name).title = getString(if (name == "socks5_port") R.string.setting_socks5_port else R.string.setting_socks5_username, prefs.getString(name, "-"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "socks5_password" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_socks5_password, if (TextUtils.isEmpty(prefs.getString(name, ""))) "-" else "*****")
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "pcap_record_size", "pcap_file_size" -> {
                if (name == "pcap_record_size") {
                    getPreferenceScreen().findPreference(name).title = getString(R.string.setting_pcap_record_size, prefs.getString(name, "64"))
                } else {
                    getPreferenceScreen().findPreference(name).title = getString(R.string.setting_pcap_file_size, prefs.getString(name, "2"))
                }
                ServiceSinkhole.setPcap(false, this)
                val pcapFile = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
                if (pcapFile.exists() && !pcapFile.delete()) {
                    Log.w(TAG, "Delete PCAP failed")
                }
                if (prefs.getBoolean("pcap", false)) {
                    ServiceSinkhole.setPcap(true, this)
                }
            }
            "watchdog" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_watchdog, prefs.getString(name, "0"))
                ServiceSinkhole.reload("changed $name", this, false)
            }
            "show_stats" -> {
                ServiceSinkhole.reloadStats("changed $name", this)
            }
            "stats_frequency" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_stats_frequency, prefs.getString(name, "1000"))
            }
            "stats_samples" -> {
                getPreferenceScreen().findPreference(name).title = getString(R.string.setting_stats_samples, prefs.getString(name, "90"))
            }
            "hosts_url" -> {
                getPreferenceScreen().findPreference(name).summary = prefs.getString(name, BuildConfig.HOSTS_FILE_URI)
            }
            "loglevel" -> {
                ServiceSinkhole.reload("changed $name", this, false)
            }
        }
    }

    private fun checkPermissions(name: String?): Boolean {
        val screen = getPreferenceScreen()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if ((name == null || "disable_on_call" == name) && prefs.getBoolean("disable_on_call", false)) {
            if (!Util.hasPhoneStatePermission(this)) {
                prefs.edit().putBoolean("disable_on_call", false).apply()
                (screen.findPreference("disable_on_call") as TwoStatePreference).isChecked = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQUEST_CALL)
                }
                if (name != null) return false
            }
        }
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val screen = getPreferenceScreen()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (requestCode == REQUEST_CALL) {
            prefs.edit().putBoolean("disable_on_call", granted).apply()
            (screen.findPreference("disable_on_call") as TwoStatePreference).isChecked = granted
        }

        if (granted) {
            ServiceSinkhole.reload("permission granted", this, false)
        }
    }

    @Throws(IllegalArgumentException::class, UnknownHostException::class)
    private fun checkAddress(address: String?, allowLocal: Boolean) {
        val addr = address?.trim()
        if (addr.isNullOrEmpty()) throw IllegalArgumentException("Bad address")
        if (!Util.isNumericAddress(addr)) throw IllegalArgumentException("Bad address")
        if (!allowLocal) {
            val iaddr = InetAddress.getByName(addr)
            if (iaddr.isLoopbackAddress || iaddr.isAnyLocalAddress) {
                throw IllegalArgumentException("Bad address")
            }
        }
    }

    @Throws(IllegalArgumentException::class, UnknownHostException::class)
    private fun checkDomain(address: String?) {
        val addr = address?.trim()
        if (addr.isNullOrEmpty()) throw IllegalArgumentException("Bad address")
        if (Util.isNumericAddress(addr)) throw IllegalArgumentException("Bad address")
        if (!PatternsCompat.DOMAIN_NAME.matcher(addr).matches()) {
            throw IllegalArgumentException("Bad address")
        }
    }

    private val interactiveStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Util.logExtras(intent)
            updateTechnicalInfo()
        }
    }

    private val connectivityChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Util.logExtras(intent)
            updateTechnicalInfo()
        }
    }

    private fun markPro(pref: Preference?, sku: String?) {
        if (pref == null) return
        if (sku == null || !IAB.isPurchased(sku, this)) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val dark = prefs.getBoolean("dark_theme", false)
            val ssb = SpannableStringBuilder("  " + pref.title)
            ssb.setSpan(ImageSpan(this, if (dark) R.drawable.ic_shopping_cart_white_24dp else R.drawable.ic_shopping_cart_black_24dp), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pref.title = ssb
        }
    }

    private fun updateTechnicalInfo() {
        val screen = getPreferenceScreen()
        val prefTechnicalInfo = screen.findPreference("technical_info")
        val prefTechnicalNetwork = screen.findPreference("technical_network")
        prefTechnicalInfo.summary = Util.getGeneralInfo(this)
        prefTechnicalNetwork.summary = Util.getNetworkInfo(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onActivityResult request=$requestCode result=$resultCode ok=${resultCode == RESULT_OK}")
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null) handleExport(data)
        } else if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null) handleImport(data)
        } else if (requestCode == REQUEST_HOSTS) {
            if (resultCode == RESULT_OK && data != null) handleHosts(data, false)
        } else if (requestCode == REQUEST_HOSTS_APPEND) {
            if (resultCode == RESULT_OK && data != null) handleHosts(data, true)
        } else {
            Log.w(TAG, "Unknown activity result request=$requestCode")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private val intentCreateExport: Intent
        get() {
            val intent: Intent
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                    intent = Intent("org.openintents.action.PICK_DIRECTORY")
                } else {
                    intent = Intent(Intent.ACTION_VIEW)
                    intent.data = Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager")
                }
            } else {
                intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*" // text/xml
                intent.putExtra(Intent.EXTRA_TITLE, "netguard_" + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date()) + ".xml")
            }
            return intent
        }

    private val intentOpenExport: Intent
        get() {
            val intent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                Intent(Intent.ACTION_GET_CONTENT)
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*" // text/xml
            return intent
        }

    private val intentOpenHosts: Intent
        get() {
            val intent = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                Intent(Intent.ACTION_GET_CONTENT)
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
            }
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*" // text/plain
            return intent
        }

    private fun handleExport(data: Intent) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            override fun doInBackground(vararg objects: Any?): Throwable? {
                var out: OutputStream? = null
                try {
                    var target = data.data
                    if (data.hasExtra("org.openintents.extra.DIR_PATH")) {
                        target = Uri.parse("$target/netguard_${SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())}.xml")
                    }
                    Log.i(TAG, "Writing URI=$target")
                    out = contentResolver.openOutputStream(target!!)
                    xmlExport(out!!)
                    return null
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    try {
                        out?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) {
                        Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun handleHosts(data: Intent, append: Boolean) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            override fun doInBackground(vararg objects: Any?): Throwable? {
                val hosts = File(filesDir, "hosts.txt")
                var out: FileOutputStream? = null
                var inputStream: InputStream? = null
                try {
                    Log.i(TAG, "Reading URI=${data.data}")
                    val resolver = contentResolver
                    val streamTypes = resolver.getStreamTypes(data.data!!, "*/*")
                    val streamType = if (streamTypes == null || streamTypes.isEmpty()) "*/*" else streamTypes[0]
                    val descriptor = resolver.openTypedAssetFileDescriptor(data.data!!, streamType, null)
                    descriptor?.use { d ->
                        inputStream = d.createInputStream()
                        out = FileOutputStream(hosts, append)
                        val buf = ByteArray(4096)
                        var len: Int
                        var total: Long = 0
                        while (inputStream!!.read(buf).also { len = it } > 0) {
                            out!!.write(buf, 0, len)
                            total += len.toLong()
                        }
                        Log.i(TAG, "Copied bytes=$total")
                    }
                    return null
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    try {
                        out?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    try {
                        inputStream?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(this@ActivitySettings)
                        val last = SimpleDateFormat.getDateTimeInstance().format(Date())
                        prefs.edit().putString("hosts_last_import", last).apply()
                        if (running) {
                            getPreferenceScreen().findPreference("hosts_import").setSummary(getString(R.string.msg_import_last, last))
                            Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                        }
                        ServiceSinkhole.reload("hosts import", this@ActivitySettings, false)
                    } else {
                        Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    private fun handleImport(data: Intent) {
        object : AsyncTask<Any?, Any?, Throwable?>() {
            override fun doInBackground(vararg objects: Any?): Throwable? {
                var inputStream: InputStream? = null
                try {
                    Log.i(TAG, "Reading URI=${data.data}")
                    val resolver = contentResolver
                    val streamTypes = resolver.getStreamTypes(data.data!!, "*/*")
                    val streamType = if (streamTypes == null || streamTypes.isEmpty()) "*/*" else streamTypes[0]
                    val descriptor = resolver.openTypedAssetFileDescriptor(data.data!!, streamType, null)
                    inputStream = descriptor?.createInputStream()
                    xmlImport(inputStream!!)
                    return null
                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    return ex
                } finally {
                    try {
                        inputStream?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }

            override fun onPostExecute(ex: Throwable?) {
                if (running) {
                    if (ex == null) {
                        Toast.makeText(this@ActivitySettings, R.string.msg_completed, Toast.LENGTH_LONG).show()
                        ServiceSinkhole.reloadStats("import", this@ActivitySettings)
                        recreate()
                    } else {
                        Toast.makeText(this@ActivitySettings, ex.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
    }

    @Throws(IOException::class)
    private fun xmlExport(os: OutputStream) {
        val bos = BufferedOutputStream(os)
        val serializer = Xml.newSerializer()
        serializer.setOutput(bos, "UTF-8")
        serializer.startDocument(null, true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.startTag(null, "netguard")

        serializer.startTag(null, "application")
        xmlExport(PreferenceManager.getDefaultSharedPreferences(this), serializer)
        serializer.endTag(null, "application")
        bos.flush()

        serializer.startTag(null, "wifi")
        xmlExport(getSharedPreferences("wifi", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "wifi")
        bos.flush()

        serializer.startTag(null, "mobile")
        xmlExport(getSharedPreferences("other", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "mobile")

        serializer.startTag(null, "screen_wifi")
        xmlExport(getSharedPreferences("screen_wifi", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "screen_wifi")

        serializer.startTag(null, "screen_other")
        xmlExport(getSharedPreferences("screen_other", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "screen_other")

        serializer.startTag(null, "roaming")
        xmlExport(getSharedPreferences("roaming", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "roaming")

        serializer.startTag(null, "lockdown")
        xmlExport(getSharedPreferences("lockdown", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "lockdown")

        serializer.startTag(null, "apply")
        xmlExport(getSharedPreferences("apply", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "apply")

        serializer.startTag(null, "notify")
        xmlExport(getSharedPreferences("notify", Context.MODE_PRIVATE), serializer)
        serializer.endTag(null, "notify")

        serializer.startTag(null, "filter")
        filterExport(serializer)
        serializer.endTag(null, "filter")

        serializer.startTag(null, "forward")
        forwardExport(serializer)
        serializer.endTag(null, "forward")

        serializer.endTag(null, "netguard")
        serializer.endDocument()
        serializer.flush()
        bos.flush()
    }

    @Throws(IOException::class)
    private fun xmlExport(prefs: SharedPreferences, serializer: XmlSerializer) {
        val settings = prefs.all
        for (key in settings.keys) {
            val value = settings[key]
            if ("imported" == key) continue
            when (value) {
                is Boolean, is Int, is String -> {
                    serializer.startTag(null, "setting")
                    serializer.attribute(null, "key", key)
                    serializer.attribute(null, "type", when (value) {
                        is Boolean -> "boolean"
                        is Int -> "integer"
                        else -> "string"
                    })
                    serializer.attribute(null, "value", value.toString())
                    serializer.endTag(null, "setting")
                }
                is Set<*> -> {
                    serializer.startTag(null, "setting")
                    serializer.attribute(null, "key", key)
                    serializer.attribute(null, "type", "set")
                    serializer.attribute(null, "value", TextUtils.join("\n", value))
                    serializer.endTag(null, "setting")
                }
                else -> Log.e(TAG, "Unknown key=$key")
            }
        }
    }

    @Throws(IOException::class)
    private fun filterExport(serializer: XmlSerializer) {
        DatabaseHelper.getInstance(this).getAccess().use { cursor ->
            val colUid = cursor.getColumnIndex("uid")
            val colVersion = cursor.getColumnIndex("version")
            val colProtocol = cursor.getColumnIndex("protocol")
            val colDAddr = cursor.getColumnIndex("daddr")
            val colDPort = cursor.getColumnIndex("dport")
            val colTime = cursor.getColumnIndex("time")
            val colBlock = cursor.getColumnIndex("block")
            while (cursor.moveToNext()) {
                for (pkg in getPackages(cursor.getInt(colUid))) {
                    serializer.startTag(null, "rule")
                    serializer.attribute(null, "pkg", pkg)
                    serializer.attribute(null, "version", cursor.getInt(colVersion).toString())
                    serializer.attribute(null, "protocol", cursor.getInt(colProtocol).toString())
                    serializer.attribute(null, "daddr", cursor.getString(colDAddr))
                    serializer.attribute(null, "dport", cursor.getInt(colDPort).toString())
                    serializer.attribute(null, "time", cursor.getLong(colTime).toString())
                    serializer.attribute(null, "block", cursor.getInt(colBlock).toString())
                    serializer.endTag(null, "rule")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun forwardExport(serializer: XmlSerializer) {
        DatabaseHelper.getInstance(this).forwarding.use { cursor ->
            val colProtocol = cursor.getColumnIndex("protocol")
            val colDPort = cursor.getColumnIndex("dport")
            val colRAddr = cursor.getColumnIndex("raddr")
            val colRPort = cursor.getColumnIndex("rport")
            val colRUid = cursor.getColumnIndex("ruid")
            while (cursor.moveToNext()) {
                for (pkg in getPackages(cursor.getInt(colRUid))) {
                    serializer.startTag(null, "port")
                    serializer.attribute(null, "pkg", pkg)
                    serializer.attribute(null, "protocol", cursor.getInt(colProtocol).toString())
                    serializer.attribute(null, "dport", cursor.getInt(colDPort).toString())
                    serializer.attribute(null, "raddr", cursor.getString(colRAddr))
                    serializer.attribute(null, "rport", cursor.getInt(colRPort).toString())
                    serializer.endTag(null, "port")
                }
            }
        }
    }

    private fun getPackages(uid: Int): Array<String> {
        return when (uid) {
            0 -> arrayOf("root")
            1013 -> arrayOf("mediaserver")
            9999 -> arrayOf("nobody")
            else -> {
                packageManager.getPackagesForUid(uid) ?: emptyArray()
            }
        }
    }

    @Throws(IOException::class, SAXException::class)
    private fun xmlImport(inputStream: InputStream) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        prefs.edit().putBoolean("enabled", false).apply()
        ServiceSinkhole.stop("import", this, false)

        val reader = SAXParserFactory.newInstance().newSAXParser().xmlReader
        val handler = XmlImportHandler(this)
        reader.contentHandler = handler
        reader.parse(InputSource(inputStream))

        xmlImport(handler.application, prefs)
        xmlImport(handler.wifi, getSharedPreferences("wifi", Context.MODE_PRIVATE))
        xmlImport(handler.mobile, getSharedPreferences("other", Context.MODE_PRIVATE))
        xmlImport(handler.screen_wifi, getSharedPreferences("screen_wifi", Context.MODE_PRIVATE))
        xmlImport(handler.screen_other, getSharedPreferences("screen_other", Context.MODE_PRIVATE))
        xmlImport(handler.roaming, getSharedPreferences("roaming", Context.MODE_PRIVATE))
        xmlImport(handler.lockdown, getSharedPreferences("lockdown", Context.MODE_PRIVATE))
        xmlImport(handler.apply, getSharedPreferences("apply", Context.MODE_PRIVATE))
        xmlImport(handler.notify, getSharedPreferences("notify", Context.MODE_PRIVATE))

        ReceiverAutostart.upgrade(true, this)
        DatabaseHelper.clearCache()
        prefs.edit().putBoolean("imported", true).apply()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    private fun xmlImport(settings: Map<String, Any?>, prefs: SharedPreferences) {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if ("enabled" != key) editor.remove(key)
        }
        for ((key, value) in settings) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                else -> Log.e(TAG, "Unknown type=" + (value?.javaClass ?: "null"))
            }
        }
        editor.apply()
    }

    private inner class XmlImportHandler(private val context: Context) : DefaultHandler() {
        var enabled = false
        val application = HashMap<String, Any?>()
        val wifi = HashMap<String, Any?>()
        val mobile = HashMap<String, Any?>()
        val screen_wifi = HashMap<String, Any?>()
        val screen_other = HashMap<String, Any?>()
        val roaming = HashMap<String, Any?>()
        val lockdown = HashMap<String, Any?>()
        val apply = HashMap<String, Any?>()
        val notify = HashMap<String, Any?>()
        private var current: MutableMap<String, Any?>? = null

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            when (qName) {
                "netguard" -> {}
                "application" -> current = application
                "wifi" -> current = wifi
                "mobile" -> current = mobile
                "screen_wifi" -> current = screen_wifi
                "screen_other" -> current = screen_other
                "roaming" -> current = roaming
                "lockdown" -> current = lockdown
                "apply" -> current = apply
                "notify" -> current = notify
                "filter" -> {
                    current = null
                    Log.i(TAG, "Clearing filters")
                    DatabaseHelper.getInstance(context).clearAccess()
                }
                "forward" -> {
                    current = null
                    Log.i(TAG, "Clearing forwards")
                    DatabaseHelper.getInstance(context).deleteForward()
                }
                "setting" -> {
                    val key = attributes.getValue("key")
                    val type = attributes.getValue("type")
                    val value = attributes.getValue("value")
                    if (current == null) {
                        Log.e(TAG, "No current key=$key")
                    } else {
                        if ("enabled" == key) {
                            enabled = value.toBoolean()
                        } else {
                            if (current == application) {
                                if (("log" == key && !IAB.isPurchased(ActivityPro.SKU_LOG, context)) ||
                                    ("theme" == key && !IAB.isPurchased(ActivityPro.SKU_THEME, context)) ||
                                    ("show_stats" == key && !IAB.isPurchased(ActivityPro.SKU_SPEED, context))
                                ) return
                                if ("hosts_last_import" == key || "hosts_last_download" == key) return
                            }
                            when (type) {
                                "boolean" -> current?.put(key, value.toBoolean())
                                "integer" -> current?.put(key, value.toInt())
                                "string" -> current?.put(key, value)
                                "set" -> {
                                    val set = HashSet<String>()
                                    if (!TextUtils.isEmpty(value)) {
                                        for (s in value.split("\n")) set.add(s)
                                    }
                                    current?.put(key, set)
                                }
                                else -> Log.e(TAG, "Unknown type key=$key")
                            }
                        }
                    }
                }
                "rule" -> {
                    val pkg = attributes.getValue("pkg")
                    val packet = Packet().apply {
                        version = attributes.getValue("version")?.toInt() ?: 4
                        protocol = attributes.getValue("protocol")?.toInt() ?: 6
                        daddr = attributes.getValue("daddr")
                        dport = attributes.getValue("dport").toInt()
                        time = attributes.getValue("time").toLong()
                    }
                    val block = attributes.getValue("block").toInt()
                    try {
                        packet.uid = getUid(pkg)
                        DatabaseHelper.getInstance(context).updateAccess(packet, null, block)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found pkg=$pkg")
                    }
                }
                "port" -> {
                    val pkg = attributes.getValue("pkg")
                    val protocol = attributes.getValue("protocol").toInt()
                    val dport = attributes.getValue("dport").toInt()
                    val raddr = attributes.getValue("raddr")
                    val rport = attributes.getValue("rport").toInt()
                    try {
                        val uid = getUid(pkg)
                        DatabaseHelper.getInstance(context).addForward(protocol, dport, raddr, rport, uid)
                    } catch (ex: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "Package not found pkg=$pkg")
                    }
                }
                else -> Log.e(TAG, "Unknown element qname=$qName")
            }
        }

        private fun getUid(pkg: String): Int {
            return when (pkg) {
                "root" -> 0
                "android.media" -> 1013
                "android.multicast" -> 1020
                "android.gps" -> 1021
                "android.dns" -> 1051
                "nobody" -> 9999
                else -> packageManager.getApplicationInfo(pkg, 0).uid
            }
        }
    }

    companion object {
        private const val TAG = "NetGuard.Settings"
        private const val REQUEST_EXPORT = 1
        private const val REQUEST_IMPORT = 2
        private const val REQUEST_HOSTS = 3
        private const val REQUEST_HOSTS_APPEND = 4
        private const val REQUEST_CALL = 5
        private val INTENT_VPN_SETTINGS = Intent("android.net.vpn.SETTINGS")
    }
}
