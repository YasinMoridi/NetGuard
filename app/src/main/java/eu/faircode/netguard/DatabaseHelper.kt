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

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDoneException
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.HashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class DatabaseHelper private constructor(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val lock = ReentrantReadWriteLock(true)

    init {
        if (!once) {
            once = true
            val dbfile = context.getDatabasePath(DB_NAME)
            if (dbfile.exists()) {
                Log.w(TAG, "Deleting $dbfile")
                dbfile.delete()
            }
            val dbjournal = context.getDatabasePath("$DB_NAME-journal")
            if (dbjournal.exists()) {
                Log.w(TAG, "Deleting $dbjournal")
                dbjournal.delete()
            }
        }
    }

    override fun close() {
        Log.w(TAG, "Database is being closed")
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating database $DB_NAME version $DB_VERSION")
        createTableLog(db)
        createTableAccess(db)
        createTableDns(db)
        createTableForward(db)
        createTableApp(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        super.onConfigure(db)
    }

    private fun createTableLog(db: SQLiteDatabase) {
        Log.i(TAG, "Creating log table")
        db.execSQL(
            "CREATE TABLE log (" +
                    " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                    ", time INTEGER NOT NULL" +
                    ", version INTEGER" +
                    ", protocol INTEGER" +
                    ", flags TEXT" +
                    ", saddr TEXT" +
                    ", sport INTEGER" +
                    ", daddr TEXT" +
                    ", dport INTEGER" +
                    ", dname TEXT" +
                    ", uid INTEGER" +
                    ", data TEXT" +
                    ", allowed INTEGER" +
                    ", connection INTEGER" +
                    ", interactive INTEGER" +
                    ");"
        )
        db.execSQL("CREATE INDEX idx_log_time ON log(time)")
        db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)")
        db.execSQL("CREATE INDEX idx_log_dname ON log(dname)")
        db.execSQL("CREATE INDEX idx_log_dport ON log(dport)")
        db.execSQL("CREATE INDEX idx_log_uid ON log(uid)")
    }

    private fun createTableAccess(db: SQLiteDatabase) {
        Log.i(TAG, "Creating access table")
        db.execSQL(
            "CREATE TABLE access (" +
                    " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                    ", uid INTEGER NOT NULL" +
                    ", version INTEGER NOT NULL" +
                    ", protocol INTEGER NOT NULL" +
                    ", daddr TEXT NOT NULL" +
                    ", dport INTEGER NOT NULL" +
                    ", time INTEGER NOT NULL" +
                    ", allowed INTEGER" +
                    ", block INTEGER NOT NULL" +
                    ", sent INTEGER" +
                    ", received INTEGER" +
                    ", connections INTEGER" +
                    ");"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_access ON access(uid, version, protocol, daddr, dport)")
        db.execSQL("CREATE INDEX idx_access_daddr ON access(daddr)")
        db.execSQL("CREATE INDEX idx_access_block ON access(block)")
    }

    private fun createTableDns(db: SQLiteDatabase) {
        Log.i(TAG, "Creating dns table")
        db.execSQL(
            "CREATE TABLE dns (" +
                    " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                    ", time INTEGER NOT NULL" +
                    ", qname TEXT NOT NULL" +
                    ", aname TEXT NOT NULL" +
                    ", resource TEXT NOT NULL" +
                    ", ttl INTEGER" +
                    ", uid INTEGER" +
                    ");"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_dns ON dns(qname, aname, resource)")
        db.execSQL("CREATE INDEX idx_dns_resource ON dns(resource)")
    }

    private fun createTableForward(db: SQLiteDatabase) {
        Log.i(TAG, "Creating forward table")
        db.execSQL(
            "CREATE TABLE forward (" +
                    " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                    ", protocol INTEGER NOT NULL" +
                    ", dport INTEGER NOT NULL" +
                    ", raddr TEXT NOT NULL" +
                    ", rport INTEGER NOT NULL" +
                    ", ruid INTEGER NOT NULL" +
                    ");"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_forward ON forward(protocol, dport)")
    }

    private fun createTableApp(db: SQLiteDatabase) {
        Log.i(TAG, "Creating app table")
        db.execSQL(
            "CREATE TABLE app (" +
                    " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
                    ", package TEXT" +
                    ", label TEXT" +
                    ", system INTEGER  NOT NULL" +
                    ", internet INTEGER NOT NULL" +
                    ", enabled INTEGER NOT NULL" +
                    ");"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_package ON app(package)")
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        var cursor: Cursor? = null
        return try {
            cursor = db.rawQuery("SELECT * FROM $table LIMIT 0", null)
            cursor.getColumnIndex(column) >= 0
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
            false
        } finally {
            cursor?.close()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "$DB_NAME upgrading from version $oldVersion to $newVersion")
        var currentVersion = oldVersion
        db.beginTransaction()
        try {
            if (currentVersion < 2) {
                if (!columnExists(db, "log", "version")) db.execSQL("ALTER TABLE log ADD COLUMN version INTEGER")
                if (!columnExists(db, "log", "protocol")) db.execSQL("ALTER TABLE log ADD COLUMN protocol INTEGER")
                if (!columnExists(db, "log", "uid")) db.execSQL("ALTER TABLE log ADD COLUMN uid INTEGER")
                currentVersion = 2
            }
            if (currentVersion < 3) {
                if (!columnExists(db, "log", "port")) db.execSQL("ALTER TABLE log ADD COLUMN port INTEGER")
                if (!columnExists(db, "log", "flags")) db.execSQL("ALTER TABLE log ADD COLUMN flags TEXT")
                currentVersion = 3
            }
            if (currentVersion < 4) {
                if (!columnExists(db, "log", "connection")) db.execSQL("ALTER TABLE log ADD COLUMN connection INTEGER")
                currentVersion = 4
            }
            if (currentVersion < 5) {
                if (!columnExists(db, "log", "interactive")) db.execSQL("ALTER TABLE log ADD COLUMN interactive INTEGER")
                currentVersion = 5
            }
            if (currentVersion < 6) {
                if (!columnExists(db, "log", "allowed")) db.execSQL("ALTER TABLE log ADD COLUMN allowed INTEGER")
                currentVersion = 6
            }
            if (currentVersion < 7) {
                db.execSQL("DROP TABLE log")
                createTableLog(db)
                currentVersion = 8
            }
            if (currentVersion < 8) {
                if (!columnExists(db, "log", "data")) db.execSQL("ALTER TABLE log ADD COLUMN data TEXT")
                db.execSQL("DROP INDEX IF EXISTS idx_log_source")
                db.execSQL("DROP INDEX IF EXISTS idx_log_dest")
                db.execSQL("CREATE INDEX idx_log_source ON log(saddr)")
                db.execSQL("CREATE INDEX idx_log_dest ON log(daddr)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_uid ON log(uid)")
                currentVersion = 8
            }
            if (currentVersion < 9) {
                createTableAccess(db)
                currentVersion = 9
            }
            if (currentVersion < 10) {
                db.execSQL("DROP TABLE IF EXISTS log")
                db.execSQL("DROP TABLE IF EXISTS access")
                createTableLog(db)
                createTableAccess(db)
                currentVersion = 10
            }
            if (currentVersion < 12) {
                db.execSQL("DROP TABLE IF EXISTS access")
                createTableAccess(db)
                currentVersion = 12
            }
            if (currentVersion < 13) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dport ON log(dport)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_dname ON log(dname)")
                currentVersion = 13
            }
            if (currentVersion < 14) {
                createTableDns(db)
                currentVersion = 14
            }
            if (currentVersion < 15) {
                db.execSQL("DROP TABLE IF EXISTS access")
                createTableAccess(db)
                currentVersion = 15
            }
            if (currentVersion < 16) {
                createTableForward(db)
                currentVersion = 16
            }
            if (currentVersion < 17) {
                if (!columnExists(db, "access", "sent")) db.execSQL("ALTER TABLE access ADD COLUMN sent INTEGER")
                if (!columnExists(db, "access", "received")) db.execSQL("ALTER TABLE access ADD COLUMN received INTEGER")
                currentVersion = 17
            }
            if (currentVersion < 18) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_block ON access(block)")
                db.execSQL("DROP INDEX IF EXISTS idx_dns")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_dns ON dns(qname, aname, resource)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_dns_resource ON dns(resource)")
                currentVersion = 18
            }
            if (currentVersion < 19) {
                if (!columnExists(db, "access", "connections")) db.execSQL("ALTER TABLE access ADD COLUMN connections INTEGER")
                currentVersion = 19
            }
            if (currentVersion < 20) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_access_daddr ON access(daddr)")
                currentVersion = 20
            }
            if (currentVersion < 21) {
                createTableApp(db)
                currentVersion = 21
            }
            if (currentVersion < 22) {
                if (!columnExists(db, "dns", "uid")) db.execSQL("ALTER TABLE dns ADD COLUMN uid INTEGER")
                currentVersion = 22
            }

            if (currentVersion == DB_VERSION) {
                db.version = currentVersion
                db.setTransactionSuccessful()
                Log.i(TAG, "$DB_NAME upgraded to $DB_VERSION")
            } else {
                throw IllegalArgumentException("$DB_NAME upgraded to $currentVersion but required $DB_VERSION")
            }
        } catch (ex: Throwable) {
            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
        } finally {
            db.endTransaction()
        }
    }

    // Log
    fun insertLog(packet: Packet, dname: String?, connection: Int, interactive: Boolean) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                if (packet.protocol == 6 && packet.daddr != null && packet.dport > 0 && packet.uid > 0 && "sni" == packet.data) {
                    val deleted = db.delete(
                        "log",
                        "time > ? AND protocol = ? AND version = ? AND flags = ? AND daddr = ? AND dport = ? AND uid = ?",
                        arrayOf(
                            (packet.time - SYN_SNI_DELAY).toString(),
                            packet.protocol.toString(),
                            packet.version.toString(),
                            "S",
                            packet.daddr,
                            packet.dport.toString(),
                            packet.uid.toString()
                        )
                    )
                    Log.i(TAG, "Deleted=$deleted packet=$packet dname=$dname")
                }
                val cv = ContentValues()
                cv.put("time", packet.time)
                cv.put("version", packet.version)
                if (packet.protocol < 0) cv.putNull("protocol") else cv.put("protocol", packet.protocol)
                cv.put("flags", packet.flags)
                cv.put("saddr", packet.saddr)
                if (packet.sport < 0) cv.putNull("sport") else cv.put("sport", packet.sport)
                cv.put("daddr", packet.daddr)
                if (packet.dport < 0) cv.putNull("dport") else cv.put("dport", packet.dport)
                if (dname == null) cv.putNull("dname") else cv.put("dname", dname)
                cv.put("data", packet.data)
                if (packet.uid < 0) cv.putNull("uid") else cv.put("uid", packet.uid)
                cv.put("allowed", if (packet.allowed) 1 else 0)
                cv.put("connection", connection)
                cv.put("interactive", if (interactive) 1 else 0)
                if (db.insert("log", null, cv) == -1L) Log.e(TAG, "Insert log failed")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyLogChanged()
    }

    fun clearLog(uid: Int) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                if (uid < 0) db.delete("log", null, arrayOf())
                else db.delete("log", "uid = ?", arrayOf(uid.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("VACUUM")
        }
        notifyLogChanged()
    }

    fun cleanupLog(time: Long) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val rows = db.delete("log", "time < ?", arrayOf(time.toString()))
                Log.i(TAG, "Cleanup log before=" + SimpleDateFormat.getDateTimeInstance().format(Date(time)) + " rows=" + rows)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getLog(udp: Boolean, tcp: Boolean, other: Boolean, allowed: Boolean, blocked: Boolean): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = buildString {
                append("SELECT ID AS _id, * FROM log WHERE (0 = 1")
                if (udp) append(" OR protocol = 17")
                if (tcp) append(" OR protocol = 6")
                if (other) append(" OR (protocol <> 6 AND protocol <> 17)")
                append(") AND (0 = 1")
                if (allowed) append(" OR allowed = 1")
                if (blocked) append(" OR allowed = 0")
                append(") ORDER BY time DESC")
            }
            return db.rawQuery(query, arrayOf())
        }
    }

    fun searchLog(find: String): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = "SELECT ID AS _id, * FROM log WHERE daddr LIKE ? OR dname LIKE ? OR dport = ? OR uid = ? ORDER BY time DESC"
            return db.rawQuery(query, arrayOf("%$find%", "%$find%", find, find))
        }
    }

    // Access
    fun updateAccess(packet: Packet, dname: String?, block: Int): Boolean {
        var rows: Int
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues()
                cv.put("time", packet.time)
                cv.put("allowed", if (packet.allowed) 1 else 0)
                if (block >= 0) cv.put("block", block)
                rows = db.update(
                    "access",
                    cv,
                    "uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?",
                    arrayOf(
                        packet.uid.toString(),
                        packet.version.toString(),
                        packet.protocol.toString(),
                        dname ?: packet.daddr,
                        packet.dport.toString()
                    )
                )
                if (rows == 0) {
                    cv.put("uid", packet.uid)
                    cv.put("version", packet.version)
                    cv.put("protocol", packet.protocol)
                    cv.put("daddr", dname ?: packet.daddr)
                    cv.put("dport", packet.dport)
                    if (block < 0) cv.put("block", block)
                    if (db.insert("access", null, cv) == -1L) Log.e(TAG, "Insert access failed")
                } else if (rows != 1) Log.e(TAG, "Update access failed rows=$rows")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
        return rows == 0
    }

    fun updateUsage(usage: Usage, dname: String?) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val selection = "uid = ? AND version = ? AND protocol = ? AND daddr = ? AND dport = ?"
                val selectionArgs = arrayOf(
                    usage.Uid.toString(),
                    usage.Version.toString(),
                    usage.Protocol.toString(),
                    dname ?: usage.DAddr,
                    usage.DPort.toString()
                )
                db.query("access", arrayOf("sent", "received", "connections"), selection, selectionArgs, null, null, null).use { cursor ->
                    var sent: Long = 0
                    var received: Long = 0
                    var connections = 0
                    val colSentIdx = cursor.getColumnIndex("sent")
                    val colReceivedIdx = cursor.getColumnIndex("received")
                    val colConnectionsIdx = cursor.getColumnIndex("connections")
                    if (cursor.moveToNext()) {
                        sent = if (cursor.isNull(colSentIdx)) 0 else cursor.getLong(colSentIdx)
                        received = if (cursor.isNull(colReceivedIdx)) 0 else cursor.getLong(colReceivedIdx)
                        connections = if (cursor.isNull(colConnectionsIdx)) 0 else cursor.getInt(colConnectionsIdx)
                    }
                    val cv = ContentValues()
                    cv.put("sent", sent + usage.Sent)
                    cv.put("received", received + usage.Received)
                    cv.put("connections", connections + 1)
                    val rows = db.update("access", cv, selection, selectionArgs)
                    if (rows != 1) Log.e(TAG, "Update usage failed rows=$rows")
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
    }

    fun setAccess(id: Long, block: Int) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues()
                cv.put("block", block)
                cv.put("allowed", -1)
                if (db.update("access", cv, "ID = ?", arrayOf(id.toString())) != 1) Log.e(TAG, "Set access failed")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
    }

    fun clearAccess() {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("access", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
    }

    fun clearAccess(uid: Int, keeprules: Boolean) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                if (keeprules) db.delete("access", "uid = ? AND block < 0", arrayOf(uid.toString()))
                else db.delete("access", "uid = ?", arrayOf(uid.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
    }

    fun resetUsage(uid: Int) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues()
                cv.putNull("sent")
                cv.putNull("received")
                cv.putNull("connections")
                db.update("access", cv, if (uid < 0) null else "uid = ?", if (uid < 0) null else arrayOf(uid.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyAccessChanged()
    }

    fun getAccess(uid: Int): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = "SELECT a.ID AS _id, a.*, (SELECT COUNT(DISTINCT d.qname) FROM dns d WHERE d.resource IN (SELECT d1.resource FROM dns d1 WHERE d1.qname = a.daddr)) count FROM access a WHERE a.uid = ? ORDER BY a.time DESC LIMIT 250"
            return db.rawQuery(query, arrayOf(uid.toString()))
        }
    }

    fun getAccess(): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            return db.query("access", null, "block >= 0", null, null, null, "uid")
        }
    }

    fun getAccessUnset(uid: Int, limit: Int, since: Long): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            var query = "SELECT MAX(time) AS time, daddr, allowed FROM access WHERE uid = ? AND block < 0 AND time >= ? GROUP BY daddr, allowed ORDER BY time DESC"
            if (limit > 0) query += " LIMIT $limit"
            return db.rawQuery(query, arrayOf(uid.toString(), since.toString()))
        }
    }

    fun getHostCount(uid: Int, usecache: Boolean): Long {
        if (usecache) {
            synchronized(mapUidHosts) {
                if (mapUidHosts.containsKey(uid)) return mapUidHosts[uid] ?: 0L
            }
        }
        lock.readLock().withLock {
            val db = readableDatabase
            val hosts = db.compileStatement("SELECT COUNT(*) FROM access WHERE block >= 0 AND uid =$uid").simpleQueryForLong()
            synchronized(mapUidHosts) {
                mapUidHosts[uid] = hosts
            }
            return hosts
        }
    }

    // DNS
    fun insertDns(rr: ResourceRecord): Boolean {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                var ttl = rr.TTL
                val min = prefs.getString("ttl", "259200")?.toInt() ?: 259200
                if (ttl < min) ttl = min
                val cv = ContentValues()
                cv.put("time", rr.Time)
                cv.put("ttl", ttl * 1000L)
                var rows = db.update("dns", cv, "qname = ? AND aname = ? AND resource = ?", arrayOf(rr.QName, rr.AName, rr.Resource))
                if (rows == 0) {
                    cv.put("qname", rr.QName)
                    cv.put("aname", rr.AName)
                    cv.put("resource", rr.Resource)
                    cv.put("uid", rr.uid)
                    if (db.insert("dns", null, cv) == -1L) Log.e(TAG, "Insert dns failed") else rows = 1
                } else if (rows != 1) Log.e(TAG, "Update dns failed rows=$rows")
                db.setTransactionSuccessful()
                return rows > 0
            } finally {
                db.endTransaction()
            }
        }
    }

    fun cleanupDns() {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val now = Date().time
                db.execSQL("DELETE FROM dns WHERE time + ttl < $now")
                Log.i(TAG, "Cleanup DNS")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun clearDns() {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("dns", null, arrayOf())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getQName(uid: Int, ip: String): String? {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = "SELECT d.qname FROM dns AS d WHERE d.resource = '${ip.replace("'", "''")}' ORDER BY (d.uid = $uid) DESC, d.qname LIMIT 1"
            return try {
                db.compileStatement(query).simpleQueryForString()
            } catch (ignored: SQLiteDoneException) {
                null
            }
        }
    }

    fun getAlternateQNames(qname: String): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = "SELECT DISTINCT d2.qname FROM dns d1 JOIN dns d2 ON d2.resource = d1.resource AND d2.id <> d1.id WHERE d1.qname = ? ORDER BY d2.qname"
            return db.rawQuery(query, arrayOf(qname))
        }
    }

    val dns: Cursor
        get() {
            lock.readLock().withLock {
                val db = readableDatabase
                val query = "SELECT ID AS _id, * FROM dns ORDER BY resource, qname"
                return db.rawQuery(query, arrayOf())
            }
        }

    fun getAccessDns(dname: String?): Cursor {
        val now = Date().time
        lock.readLock().withLock {
            val db = readableDatabase
            var query = "SELECT a.uid, a.version, a.protocol, a.daddr, d.resource, a.dport, a.block, d.time, d.ttl FROM access AS a LEFT JOIN dns AS d ON d.qname = a.daddr WHERE a.block >= 0 AND (d.time IS NULL OR d.time + d.ttl >= $now)"
            if (dname != null) query += " AND a.daddr = ?"
            return db.rawQuery(query, if (dname == null) arrayOf() else arrayOf(dname))
        }
    }

    // Forward
    fun addForward(protocol: Int, dport: Int, raddr: String, rport: Int, ruid: Int) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues()
                cv.put("protocol", protocol)
                cv.put("dport", dport)
                cv.put("raddr", raddr)
                cv.put("rport", rport)
                cv.put("ruid", ruid)
                if (db.insert("forward", null, cv) < 0) Log.e(TAG, "Insert forward failed")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyForwardChanged()
    }

    fun deleteForward() {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("forward", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyForwardChanged()
    }

    fun deleteForward(protocol: Int, dport: Int) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("forward", "protocol = ? AND dport = ?", arrayOf(protocol.toString(), dport.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        notifyForwardChanged()
    }

    val forwarding: Cursor
        get() {
            lock.readLock().withLock {
                val db = readableDatabase
                val query = "SELECT ID AS _id, * FROM forward ORDER BY dport"
                return db.rawQuery(query, arrayOf())
            }
        }

    fun addApp(packageName: String, label: String?, system: Boolean, internet: Boolean, enabled: Boolean) {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                val cv = ContentValues()
                cv.put("package", packageName)
                if (label == null) cv.putNull("label") else cv.put("label", label)
                cv.put("system", if (system) 1 else 0)
                cv.put("internet", if (internet) 1 else 0)
                cv.put("enabled", if (enabled) 1 else 0)
                if (db.insert("app", null, cv) < 0) Log.e(TAG, "Insert app failed")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getApp(packageName: String): Cursor {
        lock.readLock().withLock {
            val db = readableDatabase
            val query = "SELECT * FROM app WHERE package = ?"
            return db.rawQuery(query, arrayOf(packageName))
        }
    }

    fun clearApps() {
        lock.writeLock().withLock {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.delete("app", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun addLogChangedListener(listener: LogChangedListener) {
        logChangedListeners.add(listener)
    }

    fun removeLogChangedListener(listener: LogChangedListener) {
        logChangedListeners.remove(listener)
    }

    fun addAccessChangedListener(listener: AccessChangedListener) {
        accessChangedListeners.add(listener)
    }

    fun removeAccessChangedListener(listener: AccessChangedListener) {
        accessChangedListeners.remove(listener)
    }

    fun addForwardChangedListener(listener: ForwardChangedListener) {
        forwardChangedListeners.add(listener)
    }

    fun removeForwardChangedListener(listener: ForwardChangedListener) {
        forwardChangedListeners.remove(listener)
    }

    private fun notifyLogChanged() {
        val msg = handler.obtainMessage()
        msg.what = MSG_LOG
        handler.sendMessage(msg)
    }

    private fun notifyAccessChanged() {
        val msg = handler.obtainMessage()
        msg.what = MSG_ACCESS
        handler.sendMessage(msg)
    }

    private fun notifyForwardChanged() {
        val msg = handler.obtainMessage()
        msg.what = MSG_FORWARD
        handler.sendMessage(msg)
    }

    fun interface LogChangedListener {
        fun onChanged()
    }

    fun interface AccessChangedListener {
        fun onChanged()
    }

    fun interface ForwardChangedListener {
        fun onChanged()
    }

    companion object {
        private const val TAG = "NetGuard.Database"
        private const val DB_NAME = "Netguard"
        private const val DB_VERSION = 22
        private var once = true
        private val logChangedListeners: MutableList<LogChangedListener> = ArrayList()
        private val accessChangedListeners: MutableList<AccessChangedListener> = ArrayList()
        private val forwardChangedListeners: MutableList<ForwardChangedListener> = ArrayList()
        private var hthread: HandlerThread = HandlerThread("DatabaseHelper").apply { start() }
        private var handler: Handler = object : Handler(hthread.looper) {
            override fun handleMessage(msg: Message) {
                handleChangedNotification(msg)
            }
        }
        private val mapUidHosts: MutableMap<Int, Long> = HashMap()
        private const val MSG_LOG = 1
        private const val MSG_ACCESS = 2
        private const val MSG_FORWARD = 3
        private const val SYN_SNI_DELAY = 5000L

        @Volatile
        private var dh: DatabaseHelper? = null

        @JvmStatic
        fun getInstance(context: Context): DatabaseHelper {
            return dh ?: synchronized(this) {
                dh ?: DatabaseHelper(context.applicationContext).also { dh = it }
            }
        }

        @JvmStatic
        fun clearCache() {
            synchronized(mapUidHosts) {
                mapUidHosts.clear()
            }
        }

        private fun handleChangedNotification(msg: Message) {
            try {
                Thread.sleep(1000)
                if (handler.hasMessages(msg.what)) {
                    handler.removeMessages(msg.what)
                }
            } catch (ignored: InterruptedException) {
            }

            when (msg.what) {
                MSG_LOG -> {
                    for (listener in ArrayList(logChangedListeners)) {
                        try {
                            listener.onChanged()
                        } catch (ex: Throwable) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        }
                    }
                }
                MSG_ACCESS -> {
                    for (listener in ArrayList(accessChangedListeners)) {
                        try {
                            listener.onChanged()
                        } catch (ex: Throwable) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        }
                    }
                }
                MSG_FORWARD -> {
                    for (listener in ArrayList(forwardChangedListeners)) {
                        try {
                            listener.onChanged()
                        } catch (ex: Throwable) {
                            Log.e(TAG, ex.toString() + "\n" + Log.getStackTraceString(ex))
                        }
                    }
                }
            }
        }
    }
}
