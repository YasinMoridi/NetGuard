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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.vending.billing.IInAppBillingService
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayList

class IAB(private val delegate: Delegate, context: Context) : ServiceConnection {
    private val context: Context = context.applicationContext
    private var service: IInAppBillingService? = null

    fun interface Delegate {
        fun onReady(iab: IAB)
    }

    fun bind() {
        Log.i(TAG, "Bind")
        val serviceIntent = Intent("com.android.vending.billing.InAppBillingService.BIND")
        serviceIntent.setPackage("com.android.vending")
        context.bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (service != null) {
            Log.i(TAG, "Unbind")
            context.unbindService(this)
            service = null
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        Log.i(TAG, "Connected")
        service = IInAppBillingService.Stub.asInterface(binder)
        delegate.onReady(this)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        Log.i(TAG, "Disconnected")
        service = null
    }

    @Throws(RemoteException::class, JSONException::class)
    fun isAvailable(sku: String): Boolean {
        // Get available SKUs
        val skuList = ArrayList<String>()
        skuList.add(sku)
        val query = Bundle()
        query.putStringArrayList("ITEM_ID_LIST", skuList)
        val bundle = service?.getSkuDetails(IAB_VERSION, context.packageName, "inapp", query)
        Log.i(TAG, "getSkuDetails")
        Util.logBundle(bundle)
        val response = bundle?.getInt("RESPONSE_CODE", -1) ?: -1
        Log.i(TAG, "Response=" + getResult(response))
        if (response != 0) {
            throw IllegalArgumentException(getResult(response))
        }

        // Check available SKUs
        var found = false
        val details = bundle?.getStringArrayList("DETAILS_LIST")
        if (details != null) {
            for (item in details) {
                val `object` = JSONObject(item)
                if (sku == `object`.getString("productId")) {
                    found = true
                    break
                }
            }
        }
        Log.i(TAG, "$sku=$found")

        return found
    }

    @Throws(RemoteException::class)
    fun updatePurchases() {
        // Get purchases
        val skus = ArrayList<String>()
        skus.addAll(getPurchases("inapp"))
        skus.addAll(getPurchases("subs"))

        val prefs = context.getSharedPreferences("IAB", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (product in prefs.all.keys) {
            if (ActivityPro.SKU_DONATION != product) {
                Log.i(TAG, "removing SKU=$product")
                editor.remove(product)
            }
        }
        for (sku in skus) {
            Log.i(TAG, "adding SKU=$sku")
            editor.putBoolean(sku, true)
        }
        editor.apply()
    }

    @Throws(RemoteException::class)
    fun isPurchased(sku: String, type: String): Boolean {
        return getPurchases(type).contains(sku)
    }

    @Throws(RemoteException::class)
    fun getPurchases(type: String): List<String> {
        // Get purchases
        val bundle = service?.getPurchases(IAB_VERSION, context.packageName, type, null)
        Log.i(TAG, "getPurchases")
        Util.logBundle(bundle)
        val response = bundle?.getInt("RESPONSE_CODE", -1) ?: -1
        Log.i(TAG, "Response=" + getResult(response))
        if (response != 0) {
            throw IllegalArgumentException(getResult(response))
        }

        val details = bundle?.getStringArrayList("INAPP_PURCHASE_ITEM_LIST")
        return details ?: ArrayList()
    }

    @Throws(RemoteException::class)
    fun getBuyIntent(sku: String, subscription: Boolean): PendingIntent? {
        val s = service ?: return null
        val bundle = s.getBuyIntent(IAB_VERSION, context.packageName, sku, if (subscription) "subs" else "inapp", "netguard")
        Log.i(TAG, "getBuyIntent sku=$sku subscription=$subscription")
        Util.logBundle(bundle)
        val response = bundle?.getInt("RESPONSE_CODE", -1) ?: -1
        Log.i(TAG, "Response=" + getResult(response))
        if (response != 0) {
            throw IllegalArgumentException(getResult(response))
        }
        if (bundle == null || !bundle.containsKey("BUY_INTENT")) {
            throw IllegalArgumentException("BUY_INTENT missing")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable("BUY_INTENT", PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable("BUY_INTENT")
        }
    }

    companion object {
        private const val TAG = "NetGuard.IAB"
        private const val IAB_VERSION = 3

        @JvmStatic
        fun setBought(sku: String, context: Context) {
            Log.i(TAG, "Bought $sku")
            val prefs = context.getSharedPreferences("IAB", Context.MODE_PRIVATE)
            prefs.edit().putBoolean(sku, true).apply()
        }

        @JvmStatic
        fun isPurchased(sku: String, context: Context): Boolean {
            return try {
                if (Util.isDebuggable(context)) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    return !prefs.getBoolean("debug_iab", false)
                }

                val prefs = context.getSharedPreferences("IAB", Context.MODE_PRIVATE)
                if (ActivityPro.SKU_SUPPORT1 == sku || ActivityPro.SKU_SUPPORT2 == sku) {
                    return prefs.getBoolean(sku, false)
                }

                (prefs.getBoolean(sku, false) ||
                        prefs.getBoolean(ActivityPro.SKU_PRO1, false) ||
                        prefs.getBoolean(ActivityPro.SKU_SUPPORT1, false) ||
                        prefs.getBoolean(ActivityPro.SKU_SUPPORT2, false) ||
                        prefs.getBoolean(ActivityPro.SKU_DONATION, false))
            } catch (ignored: SecurityException) {
                false
            }
        }

        @JvmStatic
        fun isPurchasedAny(context: Context): Boolean {
            return try {
                if (Util.isDebuggable(context)) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    return !prefs.getBoolean("debug_iab", false)
                }

                val prefs = context.getSharedPreferences("IAB", Context.MODE_PRIVATE)
                for (key in prefs.all.keys) {
                    if (prefs.all[key] is Boolean && prefs.getBoolean(key, false)) {
                        return true
                    }
                }
                false
            } catch (ignored: SecurityException) {
                false
            }
        }

        @JvmStatic
        fun getResult(responseCode: Int): String {
            return when (responseCode) {
                0 -> "OK"
                1 -> "USER_CANCELED"
                2 -> "SERVICE_UNAVAILABLE"
                3 -> "BILLING_UNAVAILABLE"
                4 -> "ITEM_UNAVAILABLE"
                5 -> "DEVELOPER_ERROR"
                6 -> "ERROR"
                7 -> "ITEM_ALREADY_OWNED"
                8 -> "ITEM_NOT_OWNED"
                else -> responseCode.toString()
            }
        }
    }
}
