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

class Packet {
    @JvmField
    var time: Long = 0
    @JvmField
    var version: Int = 0
    @JvmField
    var protocol: Int = 0
    @JvmField
    var flags: String? = null
    @JvmField
    var saddr: String? = null
    @JvmField
    var sport: Int = 0
    @JvmField
    var daddr: String? = null
    @JvmField
    var dport: Int = 0
    @JvmField
    var data: String? = null
    @JvmField
    var uid: Int = 0
    @JvmField
    var allowed: Boolean = false

    override fun toString(): String {
        return "uid=$uid v$version p$protocol $daddr/$dport"
    }
}
