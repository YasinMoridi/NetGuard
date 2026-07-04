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
import android.database.Cursor
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CursorAdapter
import android.widget.TextView
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdapterDns(context: Context, cursor: Cursor) : CursorAdapter(context, cursor, 0) {
    private val colorExpired: Int

    private val colTime: Int = cursor.getColumnIndex("time")
    private val colQName: Int = cursor.getColumnIndex("qname")
    private val colAName: Int = cursor.getColumnIndex("aname")
    private val colResource: Int = cursor.getColumnIndex("resource")
    private val colTTL: Int = cursor.getColumnIndex("ttl")
    private val colUid: Int = cursor.getColumnIndex("uid")

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        colorExpired = if (prefs.getBoolean("dark_theme", false)) {
            Color.argb(128, Color.red(Color.DKGRAY), Color.green(Color.DKGRAY), Color.blue(Color.DKGRAY))
        } else {
            Color.argb(128, Color.red(Color.LTGRAY), Color.green(Color.LTGRAY), Color.blue(Color.LTGRAY))
        }
    }

    override fun newView(context: Context, cursor: Cursor, parent: ViewGroup): View {
        return LayoutInflater.from(context).inflate(R.layout.dns, parent, false)
    }

    override fun bindView(view: View, context: Context, cursor: Cursor) {
        // Get values
        val time = cursor.getLong(colTime)
        val qname = cursor.getString(colQName)
        val aname = cursor.getString(colAName)
        val resource = cursor.getString(colResource)
        val ttl = cursor.getInt(colTTL)
        val uid = cursor.getInt(colUid)

        val now = Date().time
        val expired = time + ttl < now
        view.setBackgroundColor(if (expired) colorExpired else Color.TRANSPARENT)

        // Get views
        val tvTime = view.findViewById<TextView>(R.id.tvTime)
        val tvQName = view.findViewById<TextView>(R.id.tvQName)
        val tvAName = view.findViewById<TextView>(R.id.tvAName)
        val tvResource = view.findViewById<TextView>(R.id.tvResource)
        val tvTTL = view.findViewById<TextView>(R.id.tvTTL)
        val tvUid = view.findViewById<TextView>(R.id.tvUid)

        // Set values
        tvTime.text = SimpleDateFormat("dd HH:mm", Locale.ROOT).format(time)
        tvQName.text = qname
        tvAName.text = aname
        tvResource.text = resource
        tvTTL.text = "+${ttl / 1000}"
        tvUid.text = if (uid > 0) uid.toString() else null
    }
}
