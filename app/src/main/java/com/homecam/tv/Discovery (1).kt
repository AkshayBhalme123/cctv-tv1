package com.homecam.tv

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Finds devices on the home network that accept connections on the RTSP port. */
object Discovery {

    /** e.g. "192.168.31." for a TV at 192.168.31.50 (assumes a /24 home network). */
    private fun localPrefix(): String? {
        return try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val p = a.hostAddress!!.split(".")
                        return "${p[0]}.${p[1]}.${p[2]}."
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the IPs on this network where [port] is open. Takes a few seconds. */
    fun scan(port: Int): List<String> {
        val prefix = localPrefix() ?: return emptyList()
        val found = ConcurrentLinkedQueue<String>()
        val pool = Executors.newFixedThreadPool(64)
        val tasks = (1..254).map { i ->
            Callable {
                val ip = prefix + i
                try {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 500) }
                    found.add(ip)
                } catch (e: Exception) {
                    // nothing there
                }
            }
        }
        try {
            pool.invokeAll(tasks, 20, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return found.sortedBy { it.substringAfterLast('.').toInt() }
    }
}
