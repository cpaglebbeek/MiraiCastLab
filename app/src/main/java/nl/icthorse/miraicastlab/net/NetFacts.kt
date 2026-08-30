package nl.icthorse.miraicastlab.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Kernel-level network interface enumeration.
 *
 * Why this exists at all: an unprivileged Android app cannot ask "is Miracast running?". It can,
 * however, watch the interface list. The moment a Wi-Fi Direct group is formed - which Smart View /
 * Miracast requires - the framework brings up a "p2p-*" interface. That transition is the single
 * strongest app-visible signal that a P2P session went live, and it is why this screen refreshes
 * the list every two seconds while the tester starts Smart View.
 *
 * Addresses are deliberately reduced to scope classes and counts. The lab has no reason to record
 * anyone's actual IP addresses (spec 4: collect no personal data).
 */
object NetFacts {

    /** One interface as an unprivileged app may see it. */
    data class IfaceFact(
        val name: String,
        val displayName: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
        val isVirtual: Boolean,
        val supportsMulticast: Boolean,
        val mtu: Int,
        val addressCount: Int,
        /** Scope classes present, e.g. "link-local, site-local". Never the addresses themselves. */
        val addressScopes: String,
        val ipv4Count: Int,
        val ipv6Count: Int,
    ) {
        val isP2p: Boolean get() = isP2pName(name)
        val isSoftAp: Boolean get() = isSoftApName(name)

        fun flags(): String {
            val f = mutableListOf<String>()
            f += if (isUp) "UP" else "DOWN"
            if (isLoopback) f += "LOOPBACK"
            if (isPointToPoint) f += "P2P-LINK"
            if (isVirtual) f += "VIRTUAL"
            if (supportsMulticast) f += "MULTICAST"
            return f.joinToString(",")
        }

        fun line(): String =
            name.padEnd(14) + flags() +
                (if (addressCount > 0) "  addr=" + addressCount + " (" + addressScopes + ")" else "  addr=0") +
                (if (mtu > 0) "  mtu=" + mtu else "")

        fun details(): Map<String, String> = mapOf(
            "name" to name,
            "flags" to flags(),
            "addressCount" to addressCount.toString(),
            "addressScopes" to addressScopes,
            "mtu" to mtu.toString(),
        )
    }

    /**
     * Result of one enumeration. [error] is non-null only when the enumeration itself failed, which
     * must not be confused with "there are no interfaces".
     */
    data class Snapshot(
        val interfaces: List<IfaceFact>,
        val error: String? = null,
    ) {
        val p2pInterfaces: List<IfaceFact> get() = interfaces.filter { it.isP2p }
        val activeP2p: List<IfaceFact> get() = p2pInterfaces.filter { it.isUp }
        val softApInterfaces: List<IfaceFact> get() = interfaces.filter { it.isSoftAp }
        val upInterfaces: List<IfaceFact> get() = interfaces.filter { it.isUp && !it.isLoopback }
    }

    /**
     * Wi-Fi Direct group interfaces. Android names them "p2p0" (the persistent control interface on
     * many chipsets) or "p2p-wlan0-N" / "p2p-p2p0-N" (a formed group). Both prefixes start with p2p.
     */
    fun isP2pName(name: String): Boolean = name.startsWith("p2p")

    /**
     * Samsung's SoftAP / secondary-radio interfaces. Worth watching because wireless Android Auto
     * and Miracast both want the 5 GHz radio, and a second interface appearing is evidence of how
     * the phone is dividing it up.
     */
    fun isSoftApName(name: String): Boolean =
        name.startsWith("swlan") || name.startsWith("ap0") || name == "wlan1"

    /** Enumerates every interface the kernel exposes to this uid. Never throws. */
    fun snapshot(): Snapshot = try {
        val e = NetworkInterface.getNetworkInterfaces()
        if (e == null) {
            Snapshot(emptyList(), "NetworkInterface.getNetworkInterfaces() returned null")
        } else {
            Snapshot(Collections.list(e).map { factOf(it) }.sortedBy { it.name })
        }
    } catch (t: Throwable) {
        Snapshot(emptyList(), t::class.java.simpleName + ": " + (t.message ?: "no message"))
    }

    private fun factOf(ni: NetworkInterface): IfaceFact {
        val addresses = try {
            Collections.list(ni.inetAddresses)
        } catch (t: Throwable) {
            emptyList<InetAddress>()
        }
        return IfaceFact(
            name = try { ni.name ?: "?" } catch (t: Throwable) { "?" },
            displayName = try { ni.displayName ?: "" } catch (t: Throwable) { "" },
            isUp = boolOf { ni.isUp },
            isLoopback = boolOf { ni.isLoopback },
            isPointToPoint = boolOf { ni.isPointToPoint },
            isVirtual = boolOf { ni.isVirtual },
            supportsMulticast = boolOf { ni.supportsMulticast() },
            mtu = try { ni.mtu } catch (t: Throwable) { -1 },
            addressCount = addresses.size,
            addressScopes = scopesOf(addresses),
            ipv4Count = addresses.count { it is Inet4Address },
            ipv6Count = addresses.count { it is Inet6Address },
        )
    }

    /**
     * Reduces addresses to scope classes. "global" means a routable address exists - we record that
     * it exists and nothing more.
     */
    fun scopesOf(addresses: List<InetAddress>): String {
        if (addresses.isEmpty()) return "none"
        val scopes = linkedSetOf<String>()
        for (a in addresses) {
            val s = try {
                when {
                    a.isLoopbackAddress -> "loopback"
                    a.isLinkLocalAddress -> "link-local"
                    a.isSiteLocalAddress -> "site-local"
                    a.isAnyLocalAddress -> "any-local"
                    a.isMulticastAddress -> "multicast"
                    else -> "global"
                }
            } catch (t: Throwable) {
                "unreadable"
            }
            scopes += s
        }
        return scopes.joinToString(", ")
    }

    private inline fun boolOf(block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        false
    }

    /** Wi-Fi band from a centre frequency in MHz. Returns null when the frequency is unusable. */
    fun bandOf(frequencyMhz: Int): String? = when {
        frequencyMhz <= 0 -> null
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5895 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        else -> "unknown band (" + frequencyMhz + " MHz)"
    }
}
