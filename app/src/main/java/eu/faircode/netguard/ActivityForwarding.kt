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

import android.database.Cursor
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.InetAddress
import kotlin.concurrent.thread

class ActivityForwarding : AppCompatActivity() {
    private var running = false
    private lateinit var lvForwarding: ListView
    private var adapter: AdapterForwarding? = null
    private var dialog: AlertDialog? = null

    private val listener = DatabaseHelper.ForwardChangedListener {
        runOnUiThread {
            adapter?.changeCursor(DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.forwarding)
        running = true

        supportActionBar?.apply {
            setTitle(R.string.setting_forwarding)
            setDisplayHomeAsUpEnabled(true)
        }

        lvForwarding = findViewById(R.id.lvForwarding)
        adapter = AdapterForwarding(this, DatabaseHelper.getInstance(this).forwarding)
        lvForwarding.adapter = adapter

        lvForwarding.onItemClickListener = AdapterView.OnItemClickListener { _, view, position, _ ->
            val cursor = adapter?.getItem(position) as Cursor
            val protocol = cursor.getInt(cursor.getColumnIndexOrThrow("protocol"))
            val dport = cursor.getInt(cursor.getColumnIndexOrThrow("dport"))
            val raddr = cursor.getString(cursor.getColumnIndexOrThrow("raddr"))
            val rport = cursor.getInt(cursor.getColumnIndexOrThrow("rport"))

            val popup = PopupMenu(this@ActivityForwarding, view)
            popup.inflate(R.menu.forward)
            popup.menu.findItem(R.id.menu_port).title =
                "${Util.getProtocolName(protocol, 0, false)} $dport > $raddr/$rport"

            popup.setOnMenuItemClickListener { menuItem ->
                if (menuItem.itemId == R.id.menu_delete) {
                    DatabaseHelper.getInstance(this@ActivityForwarding).deleteForward(protocol, dport)
                    ServiceSinkhole.reload("forwarding", this@ActivityForwarding, false)
                    adapter = AdapterForwarding(this@ActivityForwarding,
                        DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
                    lvForwarding.adapter = adapter
                }
                false
            }

            popup.show()
        }
    }

    override fun onResume() {
        super.onResume()
        DatabaseHelper.getInstance(this).addForwardChangedListener(listener)
        adapter?.changeCursor(DatabaseHelper.getInstance(this).forwarding)
    }

    override fun onPause() {
        super.onPause()
        DatabaseHelper.getInstance(this).removeForwardChangedListener(listener)
    }

    override fun onDestroy() {
        running = false
        adapter = null
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.forwarding, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_add -> {
                val inflater = LayoutInflater.from(this)
                val view = inflater.inflate(R.layout.forwardadd, null, false)
                val spProtocol = view.findViewById<Spinner>(R.id.spProtocol)
                val etDPort = view.findViewById<EditText>(R.id.etDPort)
                val etRAddr = view.findViewById<EditText>(R.id.etRAddr)
                val etRPort = view.findViewById<EditText>(R.id.etRPort)
                val pbRuid = view.findViewById<ProgressBar>(R.id.pbRUid)
                val spRuid = view.findViewById<Spinner>(R.id.spRUid)

                pbRuid.visibility = View.VISIBLE
                spRuid.visibility = View.GONE

                thread {
                    val rules = Rule.getRules(true, this@ActivityForwarding)
                    runOnUiThread {
                        val spinnerArrayAdapter = ArrayAdapter(this@ActivityForwarding,
                            android.R.layout.simple_spinner_item, rules)
                        spRuid.adapter = spinnerArrayAdapter
                        pbRuid.visibility = View.GONE
                        spRuid.visibility = View.VISIBLE
                    }
                }

                dialog = AlertDialog.Builder(this)
                    .setView(view)
                    .setCancelable(true)
                    .setPositiveButton(android.R.string.yes) { _, _ ->
                        try {
                            val pos = spProtocol.selectedItemPosition
                            val values = resources.getStringArray(R.array.protocolValues)
                            val protocol = values[pos].toInt()
                            val dport = etDPort.text.toString().toInt()
                            val raddr = etRAddr.text.toString()
                            val rport = etRPort.text.toString().toInt()
                            val ruid = (spRuid.selectedItem as Rule).uid

                            val iraddr = InetAddress.getByName(raddr)
                            if (rport < 1024 && (iraddr.isLoopbackAddress || iraddr.isAnyLocalAddress)) {
                                throw IllegalArgumentException("Port forwarding to privileged port on local address not possible")
                            }

                            thread {
                                try {
                                    DatabaseHelper.getInstance(this@ActivityForwarding)
                                        .addForward(protocol, dport, raddr, rport, ruid)
                                    runOnUiThread {
                                        if (running) {
                                            ServiceSinkhole.reload("forwarding", this@ActivityForwarding, false)
                                            adapter = AdapterForwarding(this@ActivityForwarding,
                                                DatabaseHelper.getInstance(this@ActivityForwarding).forwarding)
                                            lvForwarding.adapter = adapter
                                        }
                                    }
                                } catch (ex: Throwable) {
                                    runOnUiThread {
                                        if (running) {
                                            Toast.makeText(this@ActivityForwarding, ex.toString(), Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        } catch (ex: Throwable) {
                            Toast.makeText(this@ActivityForwarding, ex.toString(), Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton(android.R.string.no) { d, _ ->
                        d.dismiss()
                    }
                    .setOnDismissListener {
                        dialog = null
                    }
                    .create()
                dialog?.show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
