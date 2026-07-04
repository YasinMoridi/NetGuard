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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

@Suppress("DEPRECATION")
class DownloadTask(private val context: Context, private val url: URL, private val file: File, private val listener: Listener) : AsyncTask<Any?, Int?, Any?>() {

    private var wakeLock: PowerManager.WakeLock? = null

    interface Listener {
        fun onCompleted()
        fun onCancelled()
        fun onException(ex: Throwable)
    }

    override fun onPreExecute() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, javaClass.name)
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
        showNotification(0)
        if (!Util.isPlayStoreInstall(context)) {
            Toast.makeText(context, context.getString(R.string.msg_downloading, url.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    override fun doInBackground(vararg args: Any?): Any? {
        Log.i(TAG, "Downloading $url into $file")

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var connection: URLConnection? = null
        try {
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
            outputStream = FileOutputStream(file)

            var size: Long = 0
            val buffer = ByteArray(4096)
            var bytes: Int
            while (!isCancelled) {
                bytes = inputStream.read(buffer)
                if (bytes == -1) break
                outputStream.write(buffer, 0, bytes)

                size += bytes.toLong()
                if (contentLength > 0) {
                    publishProgress((size * 100 / contentLength).toInt())
                }
            }

            Log.i(TAG, "Downloaded size=$size")
            return null
        } catch (ex: Throwable) {
            return ex
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

    override fun onProgressUpdate(vararg progress: Int?) {
        super.onProgressUpdate(*progress)
        progress[0]?.let { showNotification(it) }
    }

    override fun onCancelled() {
        super.onCancelled()
        Log.i(TAG, "Cancelled")
        listener.onCancelled()
    }

    override fun onPostExecute(result: Any?) {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        NotificationManagerCompat.from(context).cancel(ServiceSinkhole.NOTIFY_DOWNLOAD)
        if (result is Throwable) {
            Log.e(TAG, result.toString() + "\n" + Log.getStackTraceString(result))
            listener.onException(result)
        } else {
            listener.onCompleted()
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(progress: Int) {
        val main = Intent(context, ActivitySettings::class.java)
        val pi = PendingIntentCompat.getActivity(context, ServiceSinkhole.NOTIFY_DOWNLOAD, main, PendingIntent.FLAG_UPDATE_CURRENT)

        val tv = TypedValue()
        context.theme.resolveAttribute(R.attr.colorOff, tv, true)
        val builder = NotificationCompat.Builder(context, "notify")
        builder.setSmallIcon(R.drawable.ic_file_download_white_24dp)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.msg_downloading, url.toString()))
            .setContentIntent(pi)
            .setProgress(100, progress, false)
            .setColor(tv.data)
            .setOngoing(true)
            .setAutoCancel(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
        }

        if (Util.canNotify(context)) {
            val notification = builder.build()
            NotificationManagerCompat.from(context).notify(ServiceSinkhole.NOTIFY_DOWNLOAD, notification)
        }
    }

    companion object {
        private const val TAG = "NetGuard.Download"
    }
}
