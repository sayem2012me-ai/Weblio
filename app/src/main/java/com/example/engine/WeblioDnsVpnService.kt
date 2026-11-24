package com.example.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.WeblioApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * Local-only Android VpnService that routes ONLY DNS traffic (10.111.222.2) through a TUN interface
 * to block ad/tracker domains system-wide for WEBLIO and device DNS queries without transmitting
 * traffic to any third-party VPN server.
 */
class WeblioDnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        startVpnTunnel()
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WEBLIO DNS Shield",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WEBLIO DNS Shield Active")
            .setContentText("Filtering ad and tracker DNS queries locally")
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startVpnTunnel() {
        if (vpnInterface != null) return
        val builder = Builder()
            .setSession("WEBLIO Local DNS Shield")
            .addAddress("10.111.222.1", 32)
            .addDnsServer("10.111.222.2")
            .addRoute("10.111.222.2", 32)
            .setMtu(1500)

        runCatching {
            builder.addDisallowedApplication(packageName)
        }

        vpnInterface = builder.establish() ?: run {
            stopSelf()
            return
        }
        _isRunning.value = true

        val adBlock = (application as WeblioApplication).adBlockEngine

        serviceScope.launch {
            val fd = vpnInterface ?: return@launch
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val packet = ByteArray(32767)

            while (isActive && vpnInterface != null) {
                val len = runCatching { input.read(packet) }.getOrDefault(-1)
                if (len <= 0) {
                    if (len < 0) break
                    continue
                }
                handleIpPacket(packet, len, output, adBlock)
            }
        }
    }

    private fun handleIpPacket(
        raw: ByteArray,
        len: Int,
        output: FileOutputStream,
        adBlock: AdBlockEngine
    ) {
        if (len < 28) return
        val version = (raw[0].toInt() ushr 4) and 0xF
        if (version != 4) return
        val ihl = (raw[0].toInt() and 0xF) * 4
        if (len < ihl + 8) return
        val protocol = raw[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only

        val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val udpStart = ihl
        val dnsStart = udpStart + 8
        val dnsLen = len - dnsStart
        if (dnsLen < 12) return

        val dnsQuery = raw.copyOfRange(dnsStart, len)
        val qname = parseDnsQName(dnsQuery)

        val dnsResponse: ByteArray = if (qname != null && adBlock.isDnsDomainBlocked(qname)) {
            _dnsBlockedCount.update { it + 1 }
            buildNxDomainResponse(dnsQuery)
        } else {
            forwardDnsUpstream(dnsQuery) ?: return
        }

        val replyPacket = buildIpv4UdpReply(raw, ihl, dnsResponse)
        runCatching {
            synchronized(output) {
                output.write(replyPacket)
            }
        }
    }

    private fun forwardDnsUpstream(query: ByteArray): ByteArray? {
        return runCatching {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 3500
                val upstream = InetAddress.getByName("1.1.1.1")
                socket.send(DatagramPacket(query, query.size, upstream, 53))
                val respBuf = ByteArray(4096)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPacket)
                respBuf.copyOf(respPacket.length)
            }
        }.getOrNull()
    }

    private fun stopVpn() {
        _isRunning.value = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunning.value = false
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.engine.DNS_VPN_START"
        const val ACTION_STOP = "com.example.engine.DNS_VPN_STOP"
        private const val CHANNEL_ID = "weblio_dns_vpn"
        private const val NOTIF_ID = 4102

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _dnsBlockedCount = MutableStateFlow(0L)
        val dnsBlockedCount: StateFlow<Long> = _dnsBlockedCount.asStateFlow()

        fun isPrivateDnsActive(context: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val props = cm.getLinkProperties(network) ?: return false
            return props.isPrivateDnsActive
        }

        fun parseDnsQName(dnsPacket: ByteArray): String? {
            if (dnsPacket.size < 13) return null
            val labels = mutableListOf<String>()
            var idx = 12
            while (idx < dnsPacket.size) {
                val labelLen = dnsPacket[idx].toInt() and 0xFF
                if (labelLen == 0) break
                if ((labelLen and 0xC0) != 0) break
                idx++
                if (idx + labelLen > dnsPacket.size) return null
                labels.add(String(dnsPacket, idx, labelLen, Charsets.UTF_8))
                idx += labelLen
            }
            return if (labels.isEmpty()) null else labels.joinToString(".")
        }

        fun buildNxDomainResponse(query: ByteArray): ByteArray {
            val resp = query.copyOf()
            if (resp.size >= 4) {
                // QR=1, Opcode=0, AA=1, RD=keep, RA=1, RCODE=3 (NXDOMAIN)
                resp[2] = ((resp[2].toInt() and 0x01) or 0x81).toByte()
                resp[3] = 0x83.toByte()
                // Zero answer/authority/additional counts
                if (resp.size >= 12) {
                    for (i in 6 until 12) resp[i] = 0
                }
            }
            return resp
        }

        private fun buildIpv4UdpReply(reqIp: ByteArray, reqIhl: Int, udpPayload: ByteArray): ByteArray {
            val totalLen = 20 + 8 + udpPayload.size
            val out = ByteArray(totalLen)
            // IPv4 header
            out[0] = 0x45
            out[1] = 0x00
            out[2] = ((totalLen ushr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[4] = 0x00
            out[5] = 0x01
            out[6] = 0x40
            out[7] = 0x00
            out[8] = 64 // TTL
            out[9] = 17 // UDP
            // Swap src/dst IP
            System.arraycopy(reqIp, 16, out, 12, 4)
            System.arraycopy(reqIp, 12, out, 16, 4)
            // IP checksum
            val ipSum = computeChecksum(out, 0, 20)
            out[10] = ((ipSum ushr 8) and 0xFF).toByte()
            out[11] = (ipSum and 0xFF).toByte()

            // UDP header (swap src/dst ports)
            val udpStart = 20
            out[udpStart] = reqIp[reqIhl + 2]
            out[udpStart + 1] = reqIp[reqIhl + 3]
            out[udpStart + 2] = reqIp[reqIhl]
            out[udpStart + 3] = reqIp[reqIhl + 1]
            val udpLen = 8 + udpPayload.size
            out[udpStart + 4] = ((udpLen ushr 8) and 0xFF).toByte()
            out[udpStart + 5] = (udpLen and 0xFF).toByte()
            out[udpStart + 6] = 0
            out[udpStart + 7] = 0
            System.arraycopy(udpPayload, 0, out, udpStart + 8, udpPayload.size)

            // UDP checksum optional in IPv4 (0 means not computed), compute pseudo-header checksum for strict stacks
            val udpSum = computeUdpChecksum(out, udpStart, udpLen)
            out[udpStart + 6] = ((udpSum ushr 8) and 0xFF).toByte()
            out[udpStart + 7] = (udpSum and 0xFF).toByte()
            return out
        }

        private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            while (i < offset + length - 1) {
                sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
                i += 2
            }
            if ((length and 1) != 0) {
                sum += (buf[offset + length - 1].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }

        private fun computeUdpChecksum(ipPacket: ByteArray, udpStart: Int, udpLen: Int): Int {
            val pseudo = ByteBuffer.allocate(12 + udpLen)
            pseudo.put(ipPacket, 12, 8) // src + dst IP
            pseudo.put(0.toByte())
            pseudo.put(17.toByte())
            pseudo.putShort(udpLen.toShort())
            pseudo.put(ipPacket, udpStart, udpLen)
            val arr = pseudo.array()
            val csum = computeChecksum(arr, 0, arr.size)
            return if (csum == 0) 0xFFFF else csum
        }
    }
}
