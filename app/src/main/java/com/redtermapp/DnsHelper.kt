package com.redtermapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network

object DnsHelper {
    /** DNS servers of the currently active network (carrier/router - local, fastest). */
    fun getAndroidDnsServers(context: Context): List<String> {
        val servers = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network: Network? = cm.activeNetwork
            if (network != null) {
                val lp: LinkProperties? = cm.getLinkProperties(network)
                if (lp != null) {
                    for (addr in lp.dnsServers) {
                        val host = addr.hostAddress ?: continue
                        if (!servers.contains(host)) servers.add(host)
                    }
                }
            }
        } catch (_: Exception) {}
        return servers
    }

    /**
     * Full contents for the container's /etc/resolv.conf.
     *
     * - `options` first: `single-request-reopen` fixes the parallel A/AAAA
     *   query race that intermittently surfaces as EAI_AGAIN through proot
     *   on Android, while timeout/attempts cap how long one dead server may
     *   stall a lookup.
     * - the network's own DNS first (they are local and usually respond in
     *   milliseconds), then public fallbacks in an order that never leads
     *   with a blackholed server: measured on the user's network, UDP/53 to
     *   8.8.8.8 times out while 1.1.1.1 and 9.9.9.9 answer - previously
     *   8.8.8.8 could end up first and every resolution stalled behind it
     *   (that is exactly how npm got EAI_AGAIN on registry.npmjs.org).
     */
    fun resolvConfText(context: Context): String {
        val lines = mutableListOf<String>()
        lines.add("options timeout:3 attempts:2 single-request-reopen")
        val seen = mutableSetOf<String>()
        var count = 0
        for (s in getAndroidDnsServers(context)) {
            if (seen.add(s) && count < 5) {
                lines.add("nameserver $s")
                count++
            }
        }
        if (count < 3) {
            for (s in listOf("1.1.1.1", "9.9.9.9", "8.8.8.8", "8.8.4.4")) {
                if (count >= 3) break
                if (seen.add(s)) {
                    lines.add("nameserver $s")
                    count++
                }
            }
        }
        return lines.joinToString("\n") + "\n"
    }
}
