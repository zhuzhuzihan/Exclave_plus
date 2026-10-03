/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
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

import android.net.Network
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.LocalResolver
import io.nekohasekai.sagernet.bg.proto.V2RayInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import libexclavecore.Libexclavecore
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

class V2RayTestInstance(profile: ProxyEntity, val link: String, val timeout: Int, val protectPath: String = "", val speedTestSocksPort: Int = 0) : V2RayInstance(
    profile,
), LocalResolver {
    lateinit var continuation: Continuation<Int>
    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            continuation = c
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    if (pluginConfigs.isNotEmpty()) {
                        delay(500L)
                    }
                    c.tryResume(Libexclavecore.urlTest(v2rayPoint, "", link, timeout))
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    /**
     * Speed test: download [url] through the local SOCKS inbound
     * ([speedTestSocksPort], must be > 0) and return peak bytes/sec.
     * Mirrors V2RayN (DoSpeedTest): latency is expected to be measured first
     * via [doTest]; callers skip the download when the node is unreachable.
     */
    suspend fun doSpeedTest(
        url: String,
        timeoutMs: Long = SpeedTestHelper.DEFAULT_TIMEOUT_MS,
        onProgress: ((bytesPerSec: Long) -> Unit)? = null,
    ): Long {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    if (pluginConfigs.isNotEmpty()) {
                        delay(500L)
                    }
                    val speed = withContext(Dispatchers.IO) {
                        SpeedTestHelper.download(url, speedTestSocksPort, timeoutMs, onProgress)
                    }
                    c.tryResume(speed)
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    @Volatile
    override var underlyingNetwork: Network? = null

    override suspend fun init() {
        super.init()
        v2rayPoint.withLocalResolver(this)
        v2rayPoint.withAlternativeSystemDialer(protectPath)
        DefaultNetworkListener.start(this) {
            underlyingNetwork = it
        }
    }

    override fun close() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.stop(this)
        }
        super.close()
    }

    override fun buildConfig() {
        config = buildV2RayConfig(profile, forTest = true, speedTestSocksPort = speedTestSocksPort)
    }
}