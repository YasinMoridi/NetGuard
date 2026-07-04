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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object PendingIntentCompat {
    // https://developer.android.com/about/versions/12/behavior-changes-12#pending-intent-mutability
    // Xiaomi Android 11: Too many PendingIntent created for uid nnnnn
    // https://stackoverflow.com/questions/71266853/xiaomi-android-11-securityexception-too-many-pendingintent-created

    @JvmStatic
    fun getActivity(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || flags and PendingIntent.FLAG_MUTABLE != 0) {
            PendingIntent.getActivity(context, requestCode, intent, flags)
        } else {
            PendingIntent.getActivity(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @JvmStatic
    fun getService(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || flags and PendingIntent.FLAG_MUTABLE != 0) {
            PendingIntent.getService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @JvmStatic
    fun getForegroundService(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || flags and PendingIntent.FLAG_MUTABLE != 0) {
            PendingIntent.getService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getForegroundService(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @JvmStatic
    fun getBroadcast(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || flags and PendingIntent.FLAG_MUTABLE != 0) {
            PendingIntent.getBroadcast(context, requestCode, intent, flags)
        } else {
            PendingIntent.getBroadcast(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
