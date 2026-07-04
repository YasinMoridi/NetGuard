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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.FilterQueryProvider
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NavUtils
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ActivityLog : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var running = false
    private var lvLog: ListView? = null
    private var adapter: AdapterLog? = null
    private var menuSearch: MenuItem? = null

    private var live = false
    private var resolve = false
    private var organization = false
    private var vpn4: InetAddress? = null
    private var vpn6: InetAddress? = null

    private val listener = DatabaseHelper.LogChangedListener {
        runOnUiThread {
            updateAdapter()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (!IAB.isPurchased(ActivityPro.SKU_LOG, this)) {
            startActivity(Intent(this, ActivityPro::class.java))
            finish()
        }

        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.logging)
        running = true

        // Action bar
        val actionView = layoutInflater.inflate(R.layout.actionlog, null, false)
        val swEnabled = actionView.findViewById<SwitchCompat>(R.id.swEnabled)

        supportActionBar?.apply {
            setDisplayShowCustomEnabled(true)
            customView = actionView
            setTitle(R.string.menu_log)
            setDisplayHomeAsUpEnabled(true)
        }

        // Get settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        resolve = prefs.getBoolean("resolve", false)
        organization = prefs.getBoolean("organization", false)
        val log = prefs.getBoolean("log", false)

        // Show disabled message
        val tvDisabled = findViewById<TextView>(R.id.tvDisabled)
        tvDisabled.visibility = if (log) View.GONE else View.VISIBLE

        // Set enabled switch
        swEnabled.isChecked = log
        swEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("log", isChecked).apply()
        }

        // Listen for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)

        lvLog = findViewById(R.id.lvLog)

        val udp = prefs.getBoolean("proto_udp", true)
        val tcp = prefs.getBoolean("proto_tcp", true)
        val other = prefs.getBoolean("proto_other", true)
        val allowed = prefs.getBoolean("traffic_allowed", true)
        val blocked = prefs.getBoolean("traffic_blocked", true)

        adapter = AdapterLog(
            this,
            DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked),
            resolve,
            organization
        )
        adapter?.setFilterQueryProvider(FilterQueryProvider { constraint ->
            DatabaseHelper.getInstance(this@ActivityLog).searchLog(constraint.toString())
        })

        lvLog?.adapter = adapter

        try {
            vpn4 = InetAddress.getByName(prefs.getString("vpn4", "10.1.10.1"))
            vpn6 = InetAddress.getByName(prefs.getString("vpn6", "fd00:1:fd00:1:fd00:1:fd00:1"))
        } catch (ex: UnknownHostException) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }

        lvLog?.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val pm = packageManager
            val cursor = adapter?.getItem(position) as Cursor
            val time = cursor.getLong(cursor.getColumnIndexOrThrow("time"))
            val version = cursor.getInt(cursor.getColumnIndexOrThrow("version"))
            val protocol = cursor.getInt(cursor.getColumnIndexOrThrow("protocol"))
            val saddr = cursor.getString(cursor.getColumnIndexOrThrow("saddr"))
            val sport = if (cursor.isNull(cursor.getColumnIndexOrThrow("sport"))) -1 else cursor.getInt(cursor.getColumnIndexOrThrow("sport"))
            val daddr = cursor.getString(cursor.getColumnIndexOrThrow("daddr"))
            val dport = if (cursor.isNull(cursor.getColumnIndexOrThrow("dport"))) -1 else cursor.getInt(cursor.getColumnIndexOrThrow("dport"))
            val dname = cursor.getString(cursor.getColumnIndexOrThrow("dname"))
            val uid = if (cursor.isNull(cursor.getColumnIndexOrThrow("uid"))) -1 else cursor.getInt(cursor.getColumnIndexOrThrow("uid"))
            val allowedVal = if (cursor.isNull(cursor.getColumnIndexOrThrow("allowed"))) -1 else cursor.getInt(cursor.getColumnIndexOrThrow("allowed"))

            // Get external address
            var addr: InetAddress? = null
            try {
                addr = InetAddress.getByName(daddr)
            } catch (ex: UnknownHostException) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            }

            val ip: String
            val port: Int
            if (addr == vpn4 || addr == vpn6) {
                ip = saddr
                port = sport
            } else {
                ip = daddr
                port = dport
            }

            // Build popup menu
            val popup = PopupMenu(this@ActivityLog, findViewById(R.id.vwPopupAnchor))
            popup.inflate(R.menu.log)

            // Application name
            if (uid >= 0) {
                popup.menu.findItem(R.id.menu_application).title = TextUtils.join(", ", Util.getApplicationNames(uid, this@ActivityLog))
            } else {
                popup.menu.removeItem(R.id.menu_application)
            }

            // Destination IP
            popup.menu.findItem(R.id.menu_protocol).title = Util.getProtocolName(protocol, version, false)

            // Whois
            val lookupIP = Intent(Intent.ACTION_VIEW, Uri.parse("https://search.dnslytics.com/ip/$ip"))
            if (pm.resolveActivity(lookupIP, 0) == null) {
                popup.menu.removeItem(R.id.menu_whois)
            } else {
                popup.menu.findItem(R.id.menu_whois).title = getString(R.string.title_log_whois, ip)
            }

            // Lookup port
            val lookupPort = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.speedguide.net/port.php?port=$port"))
            if (port <= 0 || pm.resolveActivity(lookupPort, 0) == null) {
                popup.menu.removeItem(R.id.menu_port)
            } else {
                popup.menu.findItem(R.id.menu_port).title = getString(R.string.title_log_port, port)
            }

            if (prefs.getBoolean("filter", false)) {
                if (uid <= 0) {
                    popup.menu.removeItem(R.id.menu_allow)
                    popup.menu.removeItem(R.id.menu_block)
                }
            } else {
                popup.menu.removeItem(R.id.menu_allow)
                popup.menu.removeItem(R.id.menu_block)
            }

            val packet = Packet().apply {
                this.version = version
                this.protocol = protocol
                this.daddr = daddr
                this.dport = dport
                this.time = time
                this.uid = uid
                this.allowed = allowedVal > 0
            }

            // Time
            popup.menu.findItem(R.id.menu_time).title = SimpleDateFormat.getDateTimeInstance().format(Date(time))

            // Handle click
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_application -> {
                        val main = Intent(this@ActivityLog, ActivityMain::class.java)
                        main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                        startActivity(main)
                        true
                    }

                    R.id.menu_whois -> {
                        startActivity(lookupIP)
                        true
                    }

                    R.id.menu_port -> {
                        startActivity(lookupPort)
                        true
                    }

                    R.id.menu_allow -> {
                        if (IAB.isPurchased(ActivityPro.SKU_FILTER, this@ActivityLog)) {
                            DatabaseHelper.getInstance(this@ActivityLog).updateAccess(packet, dname, 0)
                            ServiceSinkhole.reload("allow host", this@ActivityLog, false)
                            val main = Intent(this@ActivityLog, ActivityMain::class.java)
                            main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                            startActivity(main)
                        } else {
                            startActivity(Intent(this@ActivityLog, ActivityPro::class.java))
                        }
                        true
                    }

                    R.id.menu_block -> {
                        if (IAB.isPurchased(ActivityPro.SKU_FILTER, this@ActivityLog)) {
                            DatabaseHelper.getInstance(this@ActivityLog).updateAccess(packet, dname, 1)
                            ServiceSinkhole.reload("block host", this@ActivityLog, false)
                            val main = Intent(this@ActivityLog, ActivityMain::class.java)
                            main.putExtra(ActivityMain.EXTRA_SEARCH, uid.toString())
                            startActivity(main)
                        } else {
                            startActivity(Intent(this@ActivityLog, ActivityPro::class.java))
                        }
                        true
                    }

                    R.id.menu_copy -> {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("netguard", dname ?: daddr)
                        clipboard.setPrimaryClip(clip)
                        true
                    }

                    else -> false
                }
            }

            // Show
            popup.show()
        }

        live = true
    }

    override fun onResume() {
        super.onResume()
        if (live) {
            DatabaseHelper.getInstance(this).addLogChangedListener(listener)
            updateAdapter()
        }
    }

    override fun onPause() {
        super.onPause()
        if (live) {
            DatabaseHelper.getInstance(this).removeLogChangedListener(listener)
        }
    }

    override fun onDestroy() {
        running = false
        adapter = null
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, name: String?) {
        Log.i(TAG, "Preference $name=${prefs.all[name]}")
        if ("log" == name) {
            // Get enabled
            val log = prefs.getBoolean(name, false)

            // Display disabled warning
            val tvDisabled = findViewById<TextView>(R.id.tvDisabled)
            tvDisabled.visibility = if (log) View.GONE else View.VISIBLE

            // Check switch state
            val swEnabled = supportActionBar?.customView?.findViewById<SwitchCompat>(R.id.swEnabled)
            if (swEnabled?.isChecked != log) {
                swEnabled?.isChecked = log
            }

            ServiceSinkhole.reload("changed $name", this@ActivityLog, false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.logging, menu)

        menuSearch = menu.findItem(R.id.menu_search)
        val searchView = menuSearch?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                adapter?.filter?.filter(getUidForName(query))
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                adapter?.filter?.filter(getUidForName(newText))
                return true
            }
        })
        searchView?.setOnCloseListener {
            adapter?.filter?.filter(null)
            false
        }

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val pcapFile = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
        val export = packageManager.resolveActivity(getIntentPCAPDocument(), 0) != null

        menu.findItem(R.id.menu_protocol_udp).isChecked = prefs.getBoolean("proto_udp", true)
        menu.findItem(R.id.menu_protocol_tcp).isChecked = prefs.getBoolean("proto_tcp", true)
        menu.findItem(R.id.menu_protocol_other).isChecked = prefs.getBoolean("proto_other", true)
        menu.findItem(R.id.menu_traffic_allowed).isEnabled = prefs.getBoolean("filter", false)
        menu.findItem(R.id.menu_traffic_allowed).isChecked = prefs.getBoolean("traffic_allowed", true)
        menu.findItem(R.id.menu_traffic_blocked).isChecked = prefs.getBoolean("traffic_blocked", true)

        menu.findItem(R.id.menu_refresh).isEnabled = !menu.findItem(R.id.menu_log_live).isChecked
        menu.findItem(R.id.menu_log_resolve).isChecked = prefs.getBoolean("resolve", false)
        menu.findItem(R.id.menu_log_organization).isChecked = prefs.getBoolean("organization", false)
        menu.findItem(R.id.menu_pcap_enabled).isChecked = prefs.getBoolean("pcap", false)
        menu.findItem(R.id.menu_pcap_export).isEnabled = pcapFile.exists() && export

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val pcapFile = File(getDir("data", MODE_PRIVATE), "netguard.pcap")

        return when (item.itemId) {
            android.R.id.home -> {
                Log.i(TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
                true
            }

            R.id.menu_protocol_udp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_udp", item.isChecked()).apply()
                updateAdapter()
                true
            }

            R.id.menu_protocol_tcp -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_tcp", item.isChecked()).apply()
                updateAdapter()
                true
            }

            R.id.menu_protocol_other -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("proto_other", item.isChecked()).apply()
                updateAdapter()
                true
            }

            R.id.menu_traffic_allowed -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_allowed", item.isChecked()).apply()
                updateAdapter()
                true
            }

            R.id.menu_traffic_blocked -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("traffic_blocked", item.isChecked()).apply()
                updateAdapter()
                true
            }

            R.id.menu_log_live -> {
                item.isChecked = !item.isChecked
                live = item.isChecked
                if (live) {
                    DatabaseHelper.getInstance(this).addLogChangedListener(listener)
                    updateAdapter()
                } else {
                    DatabaseHelper.getInstance(this).removeLogChangedListener(listener)
                }
                true
            }

            R.id.menu_refresh -> {
                updateAdapter()
                true
            }

            R.id.menu_log_resolve -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("resolve", item.isChecked()).apply()
                adapter?.setResolve(item.isChecked())
                adapter?.notifyDataSetChanged()
                true
            }

            R.id.menu_log_organization -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("organization", item.isChecked()).apply()
                adapter?.setOrganization(item.isChecked())
                adapter?.notifyDataSetChanged()
                true
            }

            R.id.menu_pcap_enabled -> {
                item.isChecked = !item.isChecked
                prefs.edit().putBoolean("pcap", item.isChecked()).apply()
                ServiceSinkhole.setPcap(item.isChecked(), this@ActivityLog)
                true
            }

            R.id.menu_pcap_export -> {
                startActivityForResult(getIntentPCAPDocument(), REQUEST_PCAP)
                true
            }

            R.id.menu_log_clear -> {
                thread {
                    DatabaseHelper.getInstance(this@ActivityLog).clearLog(-1)
                    if (prefs.getBoolean("pcap", false)) {
                        ServiceSinkhole.setPcap(false, this@ActivityLog)
                        if (pcapFile.exists() && !pcapFile.delete()) {
                            Log.w(TAG, "Delete PCAP failed")
                        }
                        ServiceSinkhole.setPcap(true, this@ActivityLog)
                    } else {
                        if (pcapFile.exists() && !pcapFile.delete()) {
                            Log.w(TAG, "Delete PCAP failed")
                        }
                    }
                    runOnUiThread {
                        if (running) {
                            updateAdapter()
                        }
                    }
                }
                true
            }

            R.id.menu_log_support -> {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://github.com/M66B/NetGuard/blob/master/FAQ.md#user-content-faq27")
                }
                if (packageManager.resolveActivity(intent, 0) != null) {
                    startActivity(intent)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateAdapter() {
        if (adapter != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val udp = prefs.getBoolean("proto_udp", true)
            val tcp = prefs.getBoolean("proto_tcp", true)
            val other = prefs.getBoolean("proto_other", true)
            val allowed = prefs.getBoolean("traffic_allowed", true)
            val blocked = prefs.getBoolean("traffic_blocked", true)

            var query: String? = null
            if (menuSearch != null && menuSearch!!.isActionViewExpanded) {
                val searchView = menuSearch!!.actionView as? SearchView
                if (searchView != null) {
                    query = getUidForName(searchView.query.toString())
                }
            }

            if (TextUtils.isEmpty(query)) {
                adapter?.changeCursor(DatabaseHelper.getInstance(this).getLog(udp, tcp, other, allowed, blocked))
            } else {
                adapter?.changeCursor(DatabaseHelper.getInstance(this@ActivityLog).searchLog(query!!))
            }
        }
    }

    private fun getUidForName(query: String?): String? {
        if (query != null && query.isNotEmpty()) {
            val lowercaseQuery = query.lowercase(Locale.ROOT)
            for (rule in Rule.getRules(true, this@ActivityLog)) {
                val name = rule.name
                if (name != null && name.lowercase(Locale.ROOT).contains(lowercaseQuery)) {
                    val newQuery = rule.uid.toString()
                    Log.i(TAG, "Search $query found $name new $newQuery")
                    return newQuery
                }
            }
            Log.i(TAG, "Search $query not found")
        }
        return query
    }

    private fun getIntentPCAPDocument(): Intent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            if (Util.isPackageInstalled("org.openintents.filemanager", this)) {
                Intent("org.openintents.action.PICK_DIRECTORY")
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=org.openintents.filemanager")
                }
            }
        } else {
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_TITLE, "netguard_" + SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date().time) + ".pcap")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i(TAG, "onActivityResult request=$requestCode result=$resultCode ok=${resultCode == RESULT_OK}")

        if (requestCode == REQUEST_PCAP) {
            if (resultCode == RESULT_OK && data != null) {
                handleExportPCAP(data)
            }
        } else {
            Log.w(TAG, "Unknown activity result request=$requestCode")
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun handleExportPCAP(data: Intent) {
        thread {
            var out: OutputStream? = null
            var `in`: FileInputStream? = null
            var error: Throwable? = null
            try {
                // Stop capture
                ServiceSinkhole.setPcap(false, this@ActivityLog)

                var target = data.data
                if (data.hasExtra("org.openintents.extra.DIR_PATH")) {
                    target = Uri.parse("$target/netguard.pcap")
                }
                Log.i(TAG, "Export PCAP URI=$target")
                out = contentResolver.openOutputStream(target!!)

                val pcap = File(getDir("data", MODE_PRIVATE), "netguard.pcap")
                `in` = FileInputStream(pcap)

                var len: Int
                var total: Long = 0
                val buf = ByteArray(4096)
                while (`in`.read(buf).also { len = it } > 0) {
                    out?.write(buf, 0, len)
                    total += len.toLong()
                }
                Log.i(TAG, "Copied bytes=$total")

            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                error = ex
            } finally {
                try {
                    out?.close()
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
                try {
                    `in`?.close()
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }

                // Resume capture
                val prefs = PreferenceManager.getDefaultSharedPreferences(this@ActivityLog)
                if (prefs.getBoolean("pcap", false)) {
                    ServiceSinkhole.setPcap(true, this@ActivityLog)
                }
            }

            runOnUiThread {
                if (error == null) {
                    Toast.makeText(this@ActivityLog, R.string.msg_completed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@ActivityLog, error.toString(), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NetGuard.Log"
        private const val REQUEST_PCAP = 1
    }
}
