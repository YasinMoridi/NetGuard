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

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

class ResourceRecord {
    @JvmField var Time: Long = 0
    @JvmField var QName: String? = null
    @JvmField var AName: String? = null
    @JvmField var Resource: String? = null
    @JvmField var TTL: Int = 0
    @JvmField var uid: Int = 0

    companion object {
        private val formatter: DateFormat = SimpleDateFormat.getDateTimeInstance()
    }

    override fun toString(): String {
        return formatter.format(Date(Time).time) +
                " Q " + QName +
                " A " + AName +
                " R " + Resource +
                " TTL " + TTL +
                " uid " + uid +
                " " + formatter.format(Date(Time + TTL * 1000L).time)
    }
}
