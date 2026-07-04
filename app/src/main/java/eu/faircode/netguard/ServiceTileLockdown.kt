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
import android.content.SharedPreferences
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager

@TargetApi(Build.VERSION_CODES.N)
class ServiceTileLockdown : TileService(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onStartListening() {
        Log.i(TAG, "Start listening")
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if ("lockdown" == key) {
            update()
        }
    }

    private fun update() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lockdown = prefs.getBoolean("lockdown", false)
        val tile = qsTile
        if (tile != null) {
            tile.state = if (lockdown) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.icon = Icon.createWithResource(this, if (lockdown) R.drawable.ic_lock_outline_white_24dp else R.drawable.ic_lock_outline_white_24dp_60)
            tile.updateTile()
        }
    }

    override fun onStopListening() {
        Log.i(TAG, "Stop listening")
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onClick() {
        Log.i(TAG, "Click")

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().putBoolean("lockdown", !prefs.getBoolean("lockdown", false)).apply()
        ServiceSinkhole.reload("tile", this, false)
        WidgetLockdown.updateWidgets(this)
    }

    companion object {
        private const val TAG = "NetGuard.TileLockdown"
    }
}
