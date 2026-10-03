/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026 by dyhkwong                                             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.test

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import kotlin.math.max

/**
 * Speed test implementation, mirroring V2RayN's SpeedtestService logic:
 *
 * V2RayN starts a dedicated core instance per node (with a local SOCKS inbound),
 * measures latency first, then downloads a test file (Cachefly / Cloudflare) through
 * that SOCKS proxy, sampling throughput in 1s windows and reporting the peak as MB/s.
 *
 * Here the per-node core instance is [V2RayTestInstance] with [speedTestSocksPort] set,
 * and the download below runs through 127.0.0.1:[socksPort].
 */
object SpeedTestHelper {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 10_000

    // Overall budget for a single node download, V2RayN uses a configurable timeout (default 30s).
    const val DEFAULT_TIMEOUT_MS = 30_000L

    private const val BUFFER_SIZE = 64 * 1024
    private const val WINDOW_MS = 1000L

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) Exclave"

    /**
     * Downloads [url] through a SOCKS5 proxy at 127.0.0.1:[socksPort].
     *
     * @return peak throughput in bytes/sec (max 1s-window speed, like V2RayN).
     * @throws Exception on any failure (HTTP error, timeout, empty body, ...).
     */
    fun download(
        url: String,
        socksPort: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onProgress: ((bytesPerSec: Long) -> Unit)? = null,
    ): Long {
        require(socksPort > 0) { "invalid socks port" }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            // Cloudflare speed URLs reject requests without Referer for large files.
            connection.setRequestProperty("Referer", "https://speed.cloudflare.com/")
            connection.connect()
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                error("HTTP $code")
            }
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val buffer = ByteArray(BUFFER_SIZE)
            var windowBytes = 0L
            var windowStart = SystemClock.elapsedRealtime()
            val testStart = windowStart
            var totalBytes = 0L
            var maxSpeed = 0L
            connection.inputStream.use { input ->
                while (true) {
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    val n = input.read(buffer)
                    if (n < 0) break
                    totalBytes += n
                    windowBytes += n
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - windowStart
                    if (elapsed >= WINDOW_MS) {
                        val speed = windowBytes * 1000L / max(elapsed, 1L)
                        if (speed > maxSpeed) maxSpeed = speed
                        onProgress?.invoke(speed)
                        windowBytes = 0L
                        windowStart = now
                    }
                }
            }
            if (totalBytes <= 0L) error("no data received")
            // Account for a trailing partial window so short downloads still report.
            val totalMs = max(SystemClock.elapsedRealtime() - testStart, 1L)
            val average = totalBytes * 1000L / totalMs
            if (average > maxSpeed) maxSpeed = average
            return maxSpeed
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Formats bytes/sec like V2RayN ("12.3" MB/s, decimal units).
     */
    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec >= 1_000_000L) {
            return String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000.0)
        }
        if (bytesPerSec >= 1_000L) {
            return String.format(Locale.US, "%d KB/s", bytesPerSec / 1_000L)
        }
        return String.format(Locale.US, "%d B/s", bytesPerSec)
    }
}
