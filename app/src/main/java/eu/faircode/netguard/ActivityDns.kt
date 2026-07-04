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

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Xml
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class ActivityDns : AppCompatActivity() {
    private var running = false
    private var adapter: AdapterDns? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.resolving)

        supportActionBar?.apply {
            setTitle(R.string.setting_show_resolved)
            setDisplayHomeAsUpEnabled(true)
        }

        val lvDns = findViewById<ListView>(R.id.lvDns)
        adapter = AdapterDns(this, DatabaseHelper.getInstance(this).dns)
        lvDns.adapter = adapter

        running = true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.dns, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val pm = packageManager
        menu.findItem(R.id.menu_export).isEnabled = getIntentExport().resolveActivity(pm) != null
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_refresh -> {
                refresh()
                true
            }

            R.id.menu_cleanup -> {
                cleanup()
                true
            }

            R.id.menu_clear -> {
                Util.areYouSure(this, R.string.menu_clear) { clear() }
                true
            }

            R.id.menu_export -> {
                export()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        updateAdapter()
    }

    private fun cleanup() {
        thread {
            Log.i(TAG, "Cleanup DNS")
            DatabaseHelper.getInstance(this@ActivityDns).cleanupDns()
            runOnUiThread {
                ServiceSinkhole.reload("DNS cleanup", this@ActivityDns, false)
                updateAdapter()
            }
        }
    }

    private fun clear() {
        thread {
            Log.i(TAG, "Clear DNS")
            DatabaseHelper.getInstance(this@ActivityDns).clearDns()
            runOnUiThread {
                ServiceSinkhole.reload("DNS clear", this@ActivityDns, false)
                updateAdapter()
            }
        }
    }

    private fun export() {
        startActivityForResult(getIntentExport(), REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult request=$requestCode result=$requestCode ok=${resultCode == RESULT_OK}")
        if (requestCode == REQUEST_EXPORT) {
            if (resultCode == RESULT_OK && data != null) {
                handleExport(data)
            }
        }
    }

    private fun getIntentExport(): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // text/xml
            putExtra(Intent.EXTRA_TITLE, "netguard_dns_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date().time) + ".xml")
        }
    }

    private fun handleExport(data: Intent) {
        thread {
            var out: OutputStream? = null
            var error: Throwable? = null
            try {
                val target = data.data
                Log.i(TAG, "Writing URI=$target")
                out = contentResolver.openOutputStream(target!!)
                xmlExport(out!!)
            } catch (ex: Throwable) {
                Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                error = ex
            } finally {
                try {
                    out?.close()
                } catch (ex: IOException) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                }
            }

            runOnUiThread {
                if (running) {
                    if (error == null) {
                        Toast.makeText(this@ActivityDns, R.string.msg_completed, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@ActivityDns, error.toString(), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun xmlExport(out: OutputStream) {
        val serializer: XmlSerializer = Xml.newSerializer()
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument(null, true)
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.startTag(null, "netguard")

        val df = SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Locale.US) // RFC 822

        DatabaseHelper.getInstance(this).dns.use { cursor ->
            val colTime = cursor.getColumnIndexOrThrow("time")
            val colQName = cursor.getColumnIndexOrThrow("qname")
            val colAName = cursor.getColumnIndexOrThrow("aname")
            val colResource = cursor.getColumnIndexOrThrow("resource")
            val colTTL = cursor.getColumnIndexOrThrow("ttl")
            while (cursor.moveToNext()) {
                val time = cursor.getLong(colTime)
                val qname = cursor.getString(colQName)
                val aname = cursor.getString(colAName)
                val resource = cursor.getString(colResource)
                val ttl = cursor.getInt(colTTL)

                serializer.startTag(null, "dns")
                serializer.attribute(null, "time", df.format(Date(time)))
                serializer.attribute(null, "qname", qname)
                serializer.attribute(null, "aname", aname)
                serializer.attribute(null, "resource", resource)
                serializer.attribute(null, "ttl", ttl.toString())
                serializer.endTag(null, "dns")
            }
        }

        serializer.endTag(null, "netguard")
        serializer.endDocument()
        serializer.flush()
    }

    private fun updateAdapter() {
        adapter?.changeCursor(DatabaseHelper.getInstance(this).dns)
    }

    override fun onDestroy() {
        running = false
        adapter = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NetGuard.DNS"
        private const val REQUEST_EXPORT = 1
    }
}
