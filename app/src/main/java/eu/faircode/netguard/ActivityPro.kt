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
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NavUtils

class ActivityPro : AppCompatActivity() {
    private var iab: IAB? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "Create")
        Util.setTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pro)

        supportActionBar?.apply {
            setTitle(R.string.title_pro)
            setDisplayHomeAsUpEnabled(true)
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        // Initial state
        updateState()

        val tvLogTitle = findViewById<TextView>(R.id.tvLogTitle)
        val tvFilterTitle = findViewById<TextView>(R.id.tvFilterTitle)
        val tvNotifyTitle = findViewById<TextView>(R.id.tvNotifyTitle)
        val tvSpeedTitle = findViewById<TextView>(R.id.tvSpeedTitle)
        val tvThemeTitle = findViewById<TextView>(R.id.tvThemeTitle)
        val tvAllTitle = findViewById<TextView>(R.id.tvAllTitle)
        val tvDev1Title = findViewById<TextView>(R.id.tvDev1Title)
        val tvDev2Title = findViewById<TextView>(R.id.tvDev2Title)

        val underlineFlags = Paint.UNDERLINE_TEXT_FLAG
        tvLogTitle.paintFlags = tvLogTitle.paintFlags or underlineFlags
        tvFilterTitle.paintFlags = tvFilterTitle.paintFlags or underlineFlags
        tvNotifyTitle.paintFlags = tvNotifyTitle.paintFlags or underlineFlags
        tvSpeedTitle.paintFlags = tvSpeedTitle.paintFlags or underlineFlags
        tvThemeTitle.paintFlags = tvThemeTitle.paintFlags or underlineFlags
        tvAllTitle.paintFlags = tvAllTitle.paintFlags or underlineFlags
        tvDev1Title.paintFlags = tvDev1Title.paintFlags or underlineFlags
        tvDev2Title.paintFlags = tvDev2Title.paintFlags or underlineFlags

        val titleListener = View.OnClickListener { view ->
            val sku = when (view.id) {
                R.id.tvLogTitle -> SKU_LOG
                R.id.tvFilterTitle -> SKU_FILTER
                R.id.tvNotifyTitle -> SKU_NOTIFY
                R.id.tvSpeedTitle -> SKU_SPEED
                R.id.tvThemeTitle -> SKU_THEME
                R.id.tvAllTitle -> SKU_PRO1
                R.id.tvDev1Title -> SKU_SUPPORT1
                R.id.tvDev2Title -> SKU_SUPPORT2
                else -> SKU_PRO1
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("http://www.netguard.me/#$sku")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            }
        }

        tvLogTitle.setOnClickListener(titleListener)
        tvFilterTitle.setOnClickListener(titleListener)
        tvNotifyTitle.setOnClickListener(titleListener)
        tvSpeedTitle.setOnClickListener(titleListener)
        tvThemeTitle.setOnClickListener(titleListener)
        tvAllTitle.setOnClickListener(titleListener)
        tvDev1Title.setOnClickListener(titleListener)
        tvDev2Title.setOnClickListener(titleListener)

        try {
            iab = IAB(object : IAB.Delegate {
                override fun onReady(iab: IAB) {
                    Log.i(TAG, "IAB ready")
                    try {
                        iab.updatePurchases()
                        updateState()

                        val btnLog = findViewById<Button>(R.id.btnLog)
                        val btnFilter = findViewById<Button>(R.id.btnFilter)
                        val btnNotify = findViewById<Button>(R.id.btnNotify)
                        val btnSpeed = findViewById<Button>(R.id.btnSpeed)
                        val btnTheme = findViewById<Button>(R.id.btnTheme)
                        val btnAll = findViewById<Button>(R.id.btnAll)
                        val btnDev1 = findViewById<Button>(R.id.btnDev1)
                        val btnDev2 = findViewById<Button>(R.id.btnDev2)

                        val buttonListener = View.OnClickListener { view ->
                            try {
                                var id = 0
                                var pi: android.app.PendingIntent? = null
                                when (view) {
                                    btnLog -> {
                                        id = SKU_LOG_ID
                                        pi = iab.getBuyIntent(SKU_LOG, false)
                                    }
                                    btnFilter -> {
                                        id = SKU_FILTER_ID
                                        pi = iab.getBuyIntent(SKU_FILTER, false)
                                    }
                                    btnNotify -> {
                                        id = SKU_NOTIFY_ID
                                        pi = iab.getBuyIntent(SKU_NOTIFY, false)
                                    }
                                    btnSpeed -> {
                                        id = SKU_SPEED_ID
                                        pi = iab.getBuyIntent(SKU_SPEED, false)
                                    }
                                    btnTheme -> {
                                        id = SKU_THEME_ID
                                        pi = iab.getBuyIntent(SKU_THEME, false)
                                    }
                                    btnAll -> {
                                        id = SKU_PRO1_ID
                                        pi = iab.getBuyIntent(SKU_PRO1, false)
                                    }
                                    btnDev1 -> {
                                        id = SKU_SUPPORT1_ID
                                        pi = iab.getBuyIntent(SKU_SUPPORT1, true)
                                    }
                                    btnDev2 -> {
                                        id = SKU_SUPPORT2_ID
                                        pi = iab.getBuyIntent(SKU_SUPPORT2, true)
                                    }
                                }

                                if (id > 0 && pi != null) {
                                    startIntentSenderForResult(pi.intentSender, id, Intent(), 0, 0, 0)
                                }
                            } catch (ex: Throwable) {
                                Log.i(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                            }
                        }

                        btnLog.setOnClickListener(buttonListener)
                        btnFilter.setOnClickListener(buttonListener)
                        btnNotify.setOnClickListener(buttonListener)
                        btnSpeed.setOnClickListener(buttonListener)
                        btnTheme.setOnClickListener(buttonListener)
                        btnAll.setOnClickListener(buttonListener)
                        btnDev1.setOnClickListener(buttonListener)
                        btnDev2.setOnClickListener(buttonListener)

                        btnLog.isEnabled = true
                        btnFilter.isEnabled = true
                        btnNotify.isEnabled = true
                        btnSpeed.isEnabled = true
                        btnTheme.isEnabled = true
                        btnAll.isEnabled = true
                        btnDev1.isEnabled = true
                        btnDev2.isEnabled = true

                    } catch (ex: Throwable) {
                        Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                    }
                }
            }, this)
            iab?.bind()
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroy")
        iab?.unbind()
        iab = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.pro, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                Log.i(TAG, "Up")
                NavUtils.navigateUpFromSameTask(this)
                true
            }
            R.id.menu_challenge -> {
                menu_challenge()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (Util.isPlayStoreInstall(this)) {
            menu.removeItem(R.id.menu_challenge)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    private fun menu_challenge() {
        if (IAB.isPurchased(SKU_DONATION, this)) {
            Toast.makeText(this, getString(R.string.title_pro_already), Toast.LENGTH_LONG).show()
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.challenge, null, false)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val challenge = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) Build.SERIAL else "O3$androidId"
        val seed = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) "NetGuard2" else "NetGuard3"

        // Challenge
        val tvChallenge = view.findViewById<TextView>(R.id.tvChallenge)
        tvChallenge.text = challenge

        val ibCopy = view.findViewById<ImageButton>(R.id.ibCopy)
        ibCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.title_pro_challenge), challenge)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this@ActivityPro, android.R.string.copy, Toast.LENGTH_LONG).show()
        }

        // Response
        val etResponse = view.findViewById<EditText>(R.id.etResponse)
        try {
            val response = Util.md5(challenge, seed)
            etResponse.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(editable: Editable?) {
                    if (response.equals(editable?.toString()?.uppercase(java.util.Locale.ROOT))) {
                        IAB.setBought(SKU_DONATION, this@ActivityPro)
                        dialog.dismiss()
                        invalidateOptionsMenu()
                        updateState()
                    }
                }
            })
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        }

        val ibPaste = view.findViewById<ImageButton>(R.id.ibPaste)
        ibPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() &&
                clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
            ) {
                val item = clipboard.primaryClip?.getItemAt(0)
                etResponse.setText(item?.text?.toString())
            }
        }

        dialog.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                SKU_LOG_ID -> IAB.setBought(SKU_LOG, this)
                SKU_FILTER_ID -> IAB.setBought(SKU_FILTER, this)
                SKU_NOTIFY_ID -> IAB.setBought(SKU_NOTIFY, this)
                SKU_SPEED_ID -> IAB.setBought(SKU_SPEED, this)
                SKU_THEME_ID -> IAB.setBought(SKU_THEME, this)
                SKU_PRO1_ID -> IAB.setBought(SKU_PRO1, this)
                SKU_SUPPORT1_ID -> IAB.setBought(SKU_SUPPORT1, this)
                SKU_SUPPORT2_ID -> IAB.setBought(SKU_SUPPORT2, this)
            }
            updateState()
        }
    }

    private fun updateState() {
        val btnLog = findViewById<Button>(R.id.btnLog)
        val btnFilter = findViewById<Button>(R.id.btnFilter)
        val btnNotify = findViewById<Button>(R.id.btnNotify)
        val btnSpeed = findViewById<Button>(R.id.btnSpeed)
        val btnTheme = findViewById<Button>(R.id.btnTheme)
        val btnAll = findViewById<Button>(R.id.btnAll)
        val btnDev1 = findViewById<Button>(R.id.btnDev1)
        val btnDev2 = findViewById<Button>(R.id.btnDev2)
        val tvLog = findViewById<TextView>(R.id.tvLog)
        val tvFilter = findViewById<TextView>(R.id.tvFilter)
        val tvNotify = findViewById<TextView>(R.id.tvNotify)
        val tvSpeed = findViewById<TextView>(R.id.tvSpeed)
        val tvTheme = findViewById<TextView>(R.id.tvTheme)
        val tvAll = findViewById<TextView>(R.id.tvAll)
        val tvDev1 = findViewById<TextView>(R.id.tvDev1)
        val tvDev2 = findViewById<TextView>(R.id.tvDev2)

        val tvLogUnavailable = findViewById<TextView>(R.id.tvLogUnavailable)
        val tvFilterUnavailable = findViewById<TextView>(R.id.tvFilterUnavailable)

        val can = Util.canFilter(this)

        btnLog.visibility = if (IAB.isPurchased(SKU_LOG, this) || !can) View.GONE else View.VISIBLE
        btnFilter.visibility = if (IAB.isPurchased(SKU_FILTER, this) || !can) View.GONE else View.VISIBLE
        btnNotify.visibility = if (IAB.isPurchased(SKU_NOTIFY, this)) View.GONE else View.VISIBLE
        btnSpeed.visibility = if (IAB.isPurchased(SKU_SPEED, this)) View.GONE else View.VISIBLE
        btnTheme.visibility = if (IAB.isPurchased(SKU_THEME, this)) View.GONE else View.VISIBLE
        btnAll.visibility = if (IAB.isPurchased(SKU_PRO1, this)) View.GONE else View.VISIBLE
        btnDev1.visibility = if (IAB.isPurchased(SKU_SUPPORT1, this)) View.GONE else View.VISIBLE
        btnDev2.visibility = if (IAB.isPurchased(SKU_SUPPORT2, this)) View.GONE else View.VISIBLE

        tvLog.visibility = if (IAB.isPurchased(SKU_LOG, this) && can) View.VISIBLE else View.GONE
        tvFilter.visibility = if (IAB.isPurchased(SKU_FILTER, this) && can) View.VISIBLE else View.GONE
        tvNotify.visibility = if (IAB.isPurchased(SKU_NOTIFY, this)) View.VISIBLE else View.GONE
        tvSpeed.visibility = if (IAB.isPurchased(SKU_SPEED, this)) View.VISIBLE else View.GONE
        tvTheme.visibility = if (IAB.isPurchased(SKU_THEME, this)) View.VISIBLE else View.GONE
        tvAll.visibility = if (IAB.isPurchased(SKU_PRO1, this)) View.VISIBLE else View.GONE
        tvDev1.visibility = if (IAB.isPurchased(SKU_SUPPORT1, this)) View.VISIBLE else View.GONE
        tvDev2.visibility = if (IAB.isPurchased(SKU_SUPPORT2, this)) View.VISIBLE else View.GONE

        tvLogUnavailable.visibility = if (can) View.GONE else View.VISIBLE
        tvFilterUnavailable.visibility = if (can) View.GONE else View.VISIBLE
    }

    companion object {
        private const val TAG = "NetGuard.Pro"

        private const val SKU_LOG_ID = 1
        private const val SKU_FILTER_ID = 2
        private const val SKU_NOTIFY_ID = 3
        private const val SKU_SPEED_ID = 4
        private const val SKU_THEME_ID = 5
        private const val SKU_PRO1_ID = 6
        private const val SKU_SUPPORT1_ID = 7
        private const val SKU_SUPPORT2_ID = 8

        const val SKU_LOG = "log"
        const val SKU_FILTER = "filter"
        const val SKU_NOTIFY = "notify"
        const val SKU_SPEED = "speed"
        const val SKU_THEME = "theme"
        const val SKU_PRO1 = "pro1"
        const val SKU_SUPPORT1 = "support1"
        const val SKU_SUPPORT2 = "support2"
        const val SKU_DONATION = "donation"
    }
}
