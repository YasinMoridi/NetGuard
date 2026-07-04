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

import android.app.IntentService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.Date

@Suppress("DEPRECATION")
class ServiceExternal : IntentService(TAG) {

    override fun onHandleIntent(intent: Intent?) {
        try {
            startForeground(ServiceSinkhole.NOTIFY_EXTERNAL, getForegroundNotification(this))

            Log.i(TAG, "Received $intent")
            Util.logExtras(intent)

            if (ACTION_DOWNLOAD_HOSTS_FILE == intent?.action) {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)

                var hostsUrl = prefs.getString("hosts_url", null)
                if ("https://www.netguard.me/hosts" == hostsUrl) {
                    hostsUrl = BuildConfig.HOSTS_FILE_URI
                }

                val tmp = File(filesDir, "hosts.tmp")
                val hosts = File(filesDir, "hosts.txt")

                var inputStream: InputStream? = null
                var outputStream: OutputStream? = null
                var connection: URLConnection? = null
                try {
                    val url = URL(hostsUrl)
                    connection = url.openConnection()
                    connection.connect()

                    if (connection is HttpURLConnection) {
                        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                            throw IOException("${connection.responseCode} ${connection.responseMessage}")
                        }
                    }

                    val contentLength = connection.contentLength
                    Log.i(TAG, "Content length=$contentLength")
                    inputStream = connection.getInputStream()
                    outputStream = FileOutputStream(tmp)

                    var size: Long = 0
                    val buffer = ByteArray(4096)
                    var bytes: Int
                    while (inputStream.read(buffer).also { bytes = it } != -1) {
                        outputStream.write(buffer, 0, bytes)
                        size += bytes.toLong()
                    }

                    Log.i(TAG, "Downloaded size=$size")

                    if (hosts.exists()) {
                        hosts.delete()
                    }
                    tmp.renameTo(hosts)

                    val last = SimpleDateFormat.getDateTimeInstance().format(Date())
                    prefs.edit().putString("hosts_last_download", last).apply()

                    ServiceSinkhole.reload("hosts file download", this, false)

                } catch (ex: Throwable) {
                    Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))

                    if (tmp.exists()) {
                        tmp.delete()
                    }
                } finally {
                    try {
                        outputStream?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                    try {
                        inputStream?.close()
                    } catch (ex: IOException) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }

                    if (connection is HttpURLConnection) {
                        connection.disconnect()
                    }
                }
            }
        } finally {
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "NetGuard.External"
        private const val ACTION_DOWNLOAD_HOSTS_FILE = "eu.faircode.netguard.DOWNLOAD_HOSTS_FILE"

        private fun getForegroundNotification(context: Context): Notification {
            val builder = NotificationCompat.Builder(context, "foreground")
            builder.setSmallIcon(R.drawable.ic_hourglass_empty_white_24dp)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentTitle(context.getString(R.string.app_name))
            return builder.build()
        }
    }
}
