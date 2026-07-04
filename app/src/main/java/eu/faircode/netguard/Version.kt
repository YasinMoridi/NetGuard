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

class Version(version: String) : Comparable<Version> {
    private val version: String = version.replace("-beta", "")

    override fun compareTo(other: Version): Int {
        val lhs = this.version.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val rhs = other.version.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val length = Math.max(lhs.size, rhs.size)
        for (i in 0 until length) {
            val vLhs = if (i < lhs.size) Integer.parseInt(lhs[i]) else 0
            val vRhs = if (i < rhs.size) Integer.parseInt(rhs[i]) else 0
            if (vLhs < vRhs) return -1
            if (vLhs > vRhs) return 1
        }
        return 0
    }

    override fun toString(): String {
        return version
    }
}
