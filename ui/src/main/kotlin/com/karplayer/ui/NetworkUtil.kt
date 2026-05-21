package com.karplayer.ui

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort lookup of a non-loopback IPv4 address — used as a hint in the
 * UI when the user selects LISTENER mode so they know where the sender
 * should connect to. Returns null if no usable address was found (rare on
 * a real device that's actually on a network).
 */
internal fun findLocalIPv4(): String? = try {
    val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    ifaces
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress
} catch (_: Exception) {
    null
}
