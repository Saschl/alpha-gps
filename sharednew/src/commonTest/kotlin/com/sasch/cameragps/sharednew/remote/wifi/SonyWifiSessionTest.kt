package com.sasch.cameragps.sharednew.remote.wifi

import com.sasch.cameragps.sharednew.bluetooth.session.CameraSessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SonyWifiSessionTest : WifiLoggingTest() {
    private fun number(n: Long, size: Int) = ByteArray(size) { (n ushr (it * 8)).toByte() }
    private fun string(s: String) = byteArrayOf((s.length + 1).toByte()) +
            s.flatMap { number(it.code.toLong(), 2).toList() }.toByteArray() + byteArrayOf(0, 0)

    private inner class Camera : PtpIpConnectionFactory {
        var version = "3.00"
        var mediaServer = "Enable"
        var vendorOpen = true
        var initialTransfer = false
        var mtpMetadata = false
        var rejectOpenSession = 0
        var blockOpenSession = 0
        var rejectExit = false
        var releaseCount = 0
        var previewOpened = 0
        var previewClosed = 0
        val channels = mutableListOf<Peer>()
        val operations = mutableListOf<Triple<Int, Int, List<Long>>>()
        val saved = mutableListOf<Byte>()
        var committed = false

        inner class Peer(val session: Int, val event: Boolean) : PtpIpPacketConnection {
            val replies = Channel<PtpIpPacket>(Channel.UNLIMITED)
            var closed = false
            private var mode: Long? = null
            override suspend fun receive() = replies.receive()
            override suspend fun close() {
                closed = true; replies.close()
            }

            override suspend fun send(packet: PtpIpPacket) {
                if (packet.type == 1) {
                    replies.send(
                        PtpIpPacket(
                            2, number(session.toLong(), 4) + ByteArray(16) +
                                    byteArrayOf(67, 0, 0, 0) + number(0x10000, 4)
                        )
                    )
                    return
                }
                if (packet.type == 3) {
                    replies.send(PtpIpPacket(4, byteArrayOf())); return
                }
                if (packet.type != 6) return
                fun read(at: Int, size: Int) = (0 until size).fold(0L) { n, i ->
                    n or ((packet.body[at + i].toLong() and 255) shl (8 * i))
                }

                val code = read(4, 2).toInt()
                val id = read(6, 4).toInt()
                val params = (10 until packet.body.size step 4).map { read(it, 4) }
                operations += Triple(session, code, params)
                if (code == 0x9210 && session == blockOpenSession) return
                val rejected = (code == 0x9210 && session == rejectOpenSession) ||
                        (code == 0x9212 && params[1] == 0L && rejectExit)
                if (code == 0x9210 && !rejected) mode = params[1]
                val data: ByteArray? = when (code) {
                    0x1001 -> {
                        val ops =
                            listOf(0x1001, 0x1002, 0x1003, 0x9201, 0x9202, 0x9207, 0x9209, 0x923a) +
                                    (if (vendorOpen) listOf(0x9210) else emptyList()) +
                                    (if (initialTransfer || mode == 1L) listOf(
                                        0x1004, 0x1007, 0x9211, 0x9212,
                                        if (mtpMetadata) 0x9805 else 0x1008
                                    ) else emptyList())
                        byteArrayOf(100, 0, 6, 0, 0, 0, 1, 0, 1, 0, 0, 0, 0) +
                                number(ops.size.toLong(), 4) + ops.flatMap {
                            number(
                                it.toLong(),
                                2
                            ).toList()
                        }.toByteArray()
                    }

                    0x923a -> if (params.single() == 1L) {
                        "<X_ScalarWebAPI_LiveView_URL>http://192.168.1.1/live</X_ScalarWebAPI_LiveView_URL>".encodeToByteArray()
                    } else {
                        ("<Device><X_ServerVersion>$version</X_ServerVersion>" +
                                "<X_PTP_MediaServerSupport>$mediaServer</X_PTP_MediaServerSupport></Device>").encodeToByteArray()
                    }

                    0x9201 -> ByteArray(8)
                    0x9202 -> number(300, 2) + number(1, 4) + number(0xd295, 2) +
                            number(2, 4) + number(0xd2c1, 2) + number(0xd2c2, 2)

                    0x9209 -> number(1, 8) + number(0xd295, 2) + number(4, 2) +
                            byteArrayOf(0, 1) + number(0, 2) + number(1, 2) + byteArrayOf(0)

                    0x1004 -> number(1, 4) + number(1, 4)
                    0x1007 -> number(1, 4) + number(7, 4)
                    0x1008 -> number(1, 4) + number(0x3801, 2) + number(0, 2) + number(3, 4) +
                            ByteArray(40) + string("DSC0007.JPG") + byteArrayOf(0, 0, 0)

                    0x9805 -> {
                        fun prop(code: Long, kind: Long, value: ByteArray) =
                            number(7, 4) + number(code, 2) + number(kind, 2) + value
                        number(3, 4) + prop(0xdc02, 4, number(0x3801, 2)) +
                                prop(0xdc04, 8, number(3, 8)) + prop(
                            0xdc07,
                            0xffff,
                            string("DSC0007.JPG")
                        )
                    }

                    0x9211 -> byteArrayOf(1, 2, 3)
                    else -> null
                }
                if (data != null && !rejected) {
                    replies.send(PtpIpOperations.startData(id, data.size))
                    replies.send(PtpIpOperations.endData(id, data))
                }
                replies.send(
                    PtpIpPacket(
                        7,
                        number(if (rejected) 0x2019 else 0x2001, 2) + number(id.toLong(), 4)
                    )
                )
            }
        }

        override suspend fun connect(): PtpIpPacketConnection {
            if (channels.size % 2 == 0) assertTrue(
                channels.all { it.closed },
                "Old sockets must close before reconnect"
            )
            return Peer(channels.size / 2 + 1, channels.size % 2 == 1).also { channels += it }
        }

        suspend fun open(scope: CoroutineScope): WifiRemoteConnection = openSonyWifiSession(
            this, scope, "192.168.1.1", {
                previewOpened++
                object : SonyLiveViewByteStream {
                    override suspend fun read(): ByteArray? = awaitCancellation()
                    override suspend fun close() {
                        previewClosed++
                    }
                }
            }, {
                object : CameraImageDestination {
                    override suspend fun write(bytes: ByteArray) {
                        saved += bytes.toList()
                    }

                    override suspend fun commit() {
                        committed = true
                    }

                    override suspend fun abort() {}
                }
            }, { null }, { null }, releaseNetwork = { releaseCount++ })

        fun openModes() = operations.filter { it.second == 0x9210 }.map { it.third }
    }

    @Test
    fun switchesBothChannelsDownloadsAndReturnsToRemoteWithoutReleasingNetwork() = runTest {
        for (mtp in listOf(false, true)) {
            val camera = Camera().apply { mtpMetadata = mtp }
            val connection = camera.open(backgroundScope)
            var lost = false
            val watch = backgroundScope.launch { connection.lost.first(); lost = true }
            try {
                assertTrue(
                    connection.canTransferImages,
                    "Remote session need not advertise transfer operations"
                )
                assertTrue(connection.canCapture)
                val page = connection.openPhotoBrowser()
                assertEquals("DSC0007.JPG", page.photos.single().filename)
                assertFalse(connection.canCapture)
                assertEquals(
                    WifiImageTransferStatus.Saved,
                    connection.downloadPhoto(7, WifiPhotoDownloadFormat.Original) {}.status
                )
                assertEquals(listOf<Byte>(1, 2, 3), camera.saved)
                assertTrue(camera.committed)
                connection.openPhotoBrowser() // Refresh reuses transfer session.
                assertEquals(4, camera.channels.size)
                connection.closePhotoBrowser()
                assertTrue(connection.canCapture)
                assertEquals(
                    listOf(listOf(1L, 0L), listOf(1L, 1L), listOf(1L, 0L)),
                    camera.openModes()
                )
                assertEquals(0, camera.releaseCount)
                runCurrent()
                assertFalse(lost)
                assertEquals(
                    listOf(2L, 0L, 0L),
                    camera.operations.last { it.second == 0x9212 }.third
                )
            } finally {
                watch.cancel(); connection.close()
            }
            connection.close()
            assertTrue(camera.channels.all { it.closed })
            assertEquals(1, camera.releaseCount)
        }
    }

    @Test
    fun versionFourKeepsCombinedSessionAndLegacyWithoutMediaServerKeepsStandardSession() = runTest {
        for ((version, media) in listOf(
            "4.00" to "Enable",
            "4.00" to "Disable",
            "2.00" to "Enable",
            "3.00" to "Disable"
        )) {
            val camera = Camera().apply {
                this.version = version; mediaServer = media; initialTransfer = true
            }
            val connection = camera.open(backgroundScope)
            try {
                connection.openPhotoBrowser()
                connection.closePhotoBrowser()
                assertEquals(2, camera.channels.size)
                assertEquals(
                    if (version == "4.00") listOf(listOf(1L, 2L)) else emptyList(),
                    camera.openModes()
                )
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun separateTransferRequiresMediaServerAndVendorSessionCapabilities() = runTest {
        for ((media, vendor) in listOf(
            "Disable" to true,
            "Unknown" to true,
            "" to true,
            "Enable" to false
        )) {
            val camera = Camera().apply { mediaServer = media; vendorOpen = vendor }
            val connection = camera.open(backgroundScope)
            try {
                assertFalse(connection.canTransferImages); assertTrue(camera.openModes().isEmpty())
            } finally {
                connection.close()
            }
        }
    }

    @Test
    fun rejectedTransferSessionCanReturnToShootingAndRetry() = runTest {
        val camera = Camera().apply { rejectOpenSession = 2 }
        val connection = camera.open(backgroundScope)
        try {
            assertFailsWith<WifiRemoteConnectException> { connection.openPhotoBrowser() }
            assertTrue(camera.channels.all { it.closed })
            assertFalse(connection.canCapture)
            connection.closePhotoBrowser()
            assertTrue(connection.canCapture)
            connection.openPhotoBrowser()
            assertEquals(listOf(1L, 1L), camera.openModes().last())
        } finally {
            connection.close()
        }
        assertEquals(1, camera.releaseCount)
    }

    @Test
    fun failedExitDoesNotRestartShootingAndCanBeRetried() = runTest {
        val camera = Camera()
        val connection = camera.open(backgroundScope)
        try {
            connection.openPhotoBrowser()
            camera.rejectExit = true
            assertFailsWith<IllegalStateException> { connection.closePhotoBrowser() }
            assertFalse(connection.canCapture)
            assertEquals(4, camera.channels.size)
            camera.rejectExit = false
            connection.closePhotoBrowser()
            assertTrue(connection.canCapture)
        } finally {
            connection.close()
        }
    }

    @Test
    fun cancelledTransferOpenClosesPartialSessionAndAllowsReturn() = runTest {
        val camera = Camera().apply { blockOpenSession = 2 }
        val connection = camera.open(backgroundScope)
        try {
            val browse = launch { connection.openPhotoBrowser() }
            runCurrent()
            assertEquals(listOf(1L, 1L), camera.openModes().last())
            browse.cancelAndJoin()
            assertTrue(camera.channels.all { it.closed })
            assertEquals(0, camera.releaseCount)
            connection.closePhotoBrowser()
            assertTrue(connection.canCapture)
        } finally {
            connection.close()
        }
    }

    @Test
    fun rejectedReturnLeavesBrowserRecoverableWithoutStaleRemoteControls() = runTest {
        val camera = Camera().apply { rejectOpenSession = 3 }
        val connection = camera.open(backgroundScope)
        try {
            connection.openPhotoBrowser()
            assertFailsWith<WifiRemoteConnectException> { connection.closePhotoBrowser() }
            assertFalse(connection.canCapture)
            assertTrue(camera.channels.all { it.closed })
            connection.closePhotoBrowser()
            assertTrue(connection.canCapture)
            assertEquals(listOf(1L, 0L), camera.openModes().last())
        } finally {
            connection.close()
        }
    }

    @Test
    fun timedOutTransferOpenClosesSocketsAndDoesNotReleaseNetworkUntilDisconnect() = runTest {
        val camera = Camera().apply { blockOpenSession = 2 }
        val connection = camera.open(backgroundScope)
        try {
            assertFailsWith<WifiRemoteConnectException> { connection.openPhotoBrowser() }
            assertTrue(camera.channels.all { it.closed })
            assertEquals(0, camera.releaseCount)
            connection.prepareToDisconnect()
            assertEquals(4, camera.channels.size)
        } finally {
            connection.close()
        }
        assertEquals(1, camera.releaseCount)
    }

    @Test
    fun failedInitialSessionReleasesNetworkExactlyOnce() = runTest {
        val camera = Camera().apply { rejectOpenSession = 1 }
        assertFailsWith<WifiRemoteConnectException> { camera.open(backgroundScope) }
        assertTrue(camera.channels.all { it.closed })
        assertEquals(1, camera.releaseCount)
    }

    @Test
    fun realLossInTransferSessionIsReported() = runTest {
        val camera = Camera()
        val connection = camera.open(backgroundScope)
        try {
            connection.openPhotoBrowser()
            val loss = async { connection.lost.first() }
            camera.channels.last().replies.close()
            loss.await()
        } finally {
            connection.close()
        }
        assertTrue(camera.channels.all { it.closed })
    }

    @Test
    fun controllerStopsPreviewRestartsAfterReturnAndDoesNotReconnectDuringDisconnect() = runTest {
        val camera = Camera()
        val registry = CameraSessionRegistry()
        val controller = WifiRemoteController(
            backgroundScope, registry,
            { _, scope -> camera.open(scope) }, {}, {})
        try {
            controller.connect("camera", "192.168.1.1")
            runCurrent()
            assertEquals(1, camera.previewOpened)
            controller.browsePhotos("camera")
            runCurrent()
            assertEquals(1, camera.previewClosed)
            assertTrue(registry.get("camera")!!.wifiRemote.photoBrowser.open)
            controller.downloadPhoto("camera", 7)
            runCurrent()
            assertTrue(registry.get("camera")!!.wifiRemote.photoBrowser.savedDownloads.isNotEmpty())
            controller.leavePhotoBrowser("camera")
            runCurrent()
            assertEquals(2, camera.previewOpened)
            assertFalse(registry.get("camera")!!.wifiRemote.photoBrowser.open)
            assertTrue(
                registry.get("camera")!!.wifiRemote.photoBrowser.savedDownloads.isEmpty(),
                "Saved markers must not apply to reused handles in another MTP session"
            )
            controller.browsePhotos("camera")
            runCurrent()
            assertEquals(8, camera.channels.size)
        } finally {
            controller.closeAndJoin()
        }
        assertEquals(8, camera.channels.size, "Disconnect must not reopen remote mode")
        assertTrue(camera.channels.all { it.closed })
        assertEquals(1, camera.releaseCount)
    }
}
