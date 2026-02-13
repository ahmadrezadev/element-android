/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Mana-Commercial
 * Please see LICENSE files in the repository root for full details.
 *
 * Adapted from io.github.tim06:openvpn (Apache-2.0).
 */

package im.vector.app.features.vpn.openvpn

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.tim.basevpn.IConnectionStateListener
import com.tim.basevpn.IVPNService
import com.tim.basevpn.singleProcess.ProtocolsVpnService
import com.tim.basevpn.state.ConnectionState
import com.tim.openvpn.OpenVPNThreadv3
import com.tim.openvpn.OpenVPNThreadv3.VPNSERVICE_TUN
import com.tim.openvpn.configuration.OpenVPNConfig
import com.tim.openvpn.log.OpenVPNLogger
import com.tim.openvpn.model.CIDRIP
import com.tim.openvpn.service.IOpenVPNService
import com.tim.openvpn.utils.NetworkSpace
import com.tim.openvpn.utils.NetworkSpace.IpAddress
import com.tim.openvpn.utils.NetworkUtils
import im.vector.app.features.vpn.VpnRuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

class ManaOpenVpnService : ProtocolsVpnService(), Handler.Callback, IOpenVPNService {

    private var management: OpenVPNThreadv3? = null
    private var config: OpenVPNConfig? = null
    private var username: String? = null
    private var password: String? = null
    private var privateKeyPassword: String? = null
    private var job: Job? = null
    @Volatile
    private var currentState: ConnectionState = ConnectionState.IDLE

    private val vpnServiceBinder = object : IVPNService.Stub() {
        override fun startVPN() {
            start()
        }

        override fun stopVPN() {
            stop()
        }

        override fun getState(): ConnectionState = currentState

        override fun registerCallback(cb: IConnectionStateListener?) {
            callbackList?.register(cb)
            cb?.stateChanged(currentState)
        }

        override fun unregisterCallback(cb: IConnectionStateListener?) {
            callbackList?.unregister(cb)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        return if (intent.action == VPN_SERVICE_ACTION) {
            null
        } else {
            super.onBind(intent)
            vpnServiceBinder
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentState = when (intent?.getStringExtra(ACTION_KEY)) {
            ACTION_START_KEY -> ConnectionState.CONNECTING
            ACTION_STOP_KEY -> ConnectionState.DISCONNECTING
            else -> currentState
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun handleMessage(msg: Message): Boolean {
        val callback = msg.callback
        return if (callback != null) {
            callback.run()
            true
        } else {
            false
        }
    }

    override fun onRevoke() {
        endVpnService()
        updateConnectionState(ConnectionState.DISCONNECTED)
        super.onRevoke()
    }

    override fun prepare(intent: Intent) {
        val action = intent.getStringExtra(ACTION_KEY)
        if (action != ACTION_START_KEY) {
            config = null
            username = null
            password = null
            privateKeyPassword = null
            return
        }
        config = intent.parseConfiguration()
        username = intent.parseUsername()
        password = intent.parsePassword()
        privateKeyPassword = intent.parsePrivateKeyPassword()
        if (config == null) {
            Log.e(TAG, "prepare(): missing OpenVPNConfig in start intent")
        } else {
            Log.d(
                    TAG,
                    "prepare(): config loaded name=${config?.name ?: "null"} host=${config?.host ?: "null"} port=${config?.port ?: "null"} hasInlineConfig=${!config?.configuration.isNullOrBlank()} hasCredentials=${!username.isNullOrBlank() && !password.isNullOrBlank()} hasPrivateKeyPassword=${!privateKeyPassword.isNullOrBlank()}"
            )
        }
    }

    override fun start() {
        startOpenVPN()
    }

    override fun stop() {
        stopOpenVPN()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentState == ConnectionState.CONNECTING || currentState == ConnectionState.READYFORCONNECT) {
            VpnRuntimeDiagnostics.writeLastFailure(
                    applicationContext,
                    "OpenVPN service was destroyed while state=$currentState"
            )
        }
        runCatching { stopSelf() }
    }

    private fun startOpenVPN() {
        VpnRuntimeDiagnostics.clearLastFailure(applicationContext)
        showNotification()
        val config = requireNotNull(config)
        val inlineConfig = config.configuration ?: config.buildConfig()
        Log.d(TAG, "startOpenVPN(): starting OpenVPN thread (configChars=${inlineConfig.length})")
        val localManagement = ManaOpenVpnThread(
                this,
                appContext = applicationContext,
                inlineConfig = inlineConfig,
                contentResolver = contentResolver,
                username = username,
                password = password,
                privateKeyPassword = privateKeyPassword,
        )
        if (!localManagement.configure()) {
            Log.e(TAG, "startOpenVPN(): OpenVPN preflight parse error")
            VpnRuntimeDiagnostics.writeLastFailure(
                    applicationContext,
                    "OpenVPN preflight parse error"
            )
            updateConnectionState(ConnectionState.DISCONNECTED)
            return
        }
        management = localManagement
        job = lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "startOpenVPN(): connect() entered")
            runCatching {
                val connectStatus = localManagement.connect()
                Log.d(
                        TAG,
                        String.format(
                                Locale.US,
                                "startOpenVPN(): connect() returned error=%s status='%s' message='%s'",
                                connectStatus.error,
                                connectStatus.status,
                                connectStatus.message
                        )
                )
                if (connectStatus.error) {
                    VpnRuntimeDiagnostics.writeLastFailure(
                            applicationContext,
                            "connectStatus error: status='${connectStatus.status}', message='${connectStatus.message}'"
                    )
                    updateConnectionState(ConnectionState.DISCONNECTED)
                }
            }.onFailure { failure ->
                Log.e(TAG, "startOpenVPN(): connect() failed: ${failure.localizedMessage}", failure)
                VpnRuntimeDiagnostics.writeLastFailure(
                        applicationContext,
                        "connect() failed: ${failure.localizedMessage ?: failure::class.java.simpleName}"
                )
                updateConnectionState(ConnectionState.DISCONNECTED)
            }
            Log.d(TAG, "startOpenVPN(): connect() finished")
        }
    }

    private fun stopOpenVPN() {
        val localManagement = management ?: return
        runCatching {
            localManagement.stop()
        }.onFailure { failure ->
            Log.w(TAG, "stopOpenVPN(): stop() failed: ${failure.localizedMessage}")
        }
    }

    private fun startTun(): ParcelFileDescriptor? {
        val builder = Builder().apply {
            if (localIp != null) {
                addLocalNetworksToRoutes()
                try {
                    localIp?.let { addAddress(it.ip, it.len) }
                } catch (iae: IllegalArgumentException) {
                    OpenVPNLogger.e("ManaOpenVpnService", "Error: $localIp ${iae.localizedMessage}")
                    return null
                }
            }

            if (localIPv6 != null) {
                val ipv6parts = localIPv6!!.split("/").toTypedArray()
                try {
                    addAddress(ipv6parts[0], ipv6parts[1].toInt())
                } catch (iae: IllegalArgumentException) {
                    OpenVPNLogger.e("ManaOpenVpnService", "Error: $localIPv6 ${iae.localizedMessage}")
                    return null
                }
            }

            dnsList.forEach { addDnsServer(it) }

            mtu?.let { setMtu(it) }

            val positiveIPv4Routes = routesV4.getPositiveIPList()
            val positiveIPv6Routes = routesV6.getPositiveIPList()

            if ("samsung" == Build.BRAND && dnsList.isNotEmpty()) {
                try {
                    val dnsServer = IpAddress(CIDRIP(dnsList[0], 32), true)
                    var dnsIncluded = false
                    for (net in positiveIPv4Routes) {
                        if (net.containsNet(dnsServer)) {
                            dnsIncluded = true
                        }
                    }
                    if (!dnsIncluded) {
                        OpenVPNLogger.e(
                                "ManaOpenVpnService",
                                "Samsung workaround: route to DNS ${dnsList[0]} was added to VPN routes"
                        )
                        positiveIPv4Routes.add(dnsServer)
                    }
                } catch (failure: Exception) {
                    if (!dnsList[0].contains(":")) {
                        OpenVPNLogger.e("ManaOpenVpnService", "Error parsing DNS server: ${dnsList[0]}")
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                installRoutesExcluded(this, routesV4)
                installRoutesExcluded(this, routesV6)
            } else {
                installRoutesPositiveOnly(this, positiveIPv4Routes, positiveIPv6Routes)
            }

            if (domain != null) {
                addSearchDomain(domain!!)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setUnderlyingNetworks(null)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
            }

            // Restrict tunneled traffic only when explicit app allow-list is provided.
            applyAllowedApplications(this)

            setSession("Mana OpenVPN Session")
        }

        return runCatching {
            builder.establish()
                    ?: throw NullPointerException("Android establish() returned null")
        }.getOrElse {
            OpenVPNLogger.e("ManaOpenVpnService", "Failed to establish TUN: ${it.localizedMessage}")
            null
        }
    }

    private fun applyAllowedApplications(builder: VpnService.Builder) {
        val packages = allowedApplications.orEmpty().filter { it.isNotBlank() }
        packages.forEach { packageName ->
            runCatching { builder.addAllowedApplication(packageName) }
                    .onFailure {
                        OpenVPNLogger.e(
                                "ManaOpenVpnService",
                                "Failed to add allowed package '$packageName': ${it.localizedMessage}"
                        )
                    }
        }
    }

    private fun addLocalNetworksToRoutes() {
        for (net in NetworkUtils.getLocalNetworks(connectivityManager, false)) {
            val parts = net.split("/").toTypedArray()
            val ipAddr = parts[0]
            if (ipAddr == localIp?.ip) continue
        }
        for (net in NetworkUtils.getLocalNetworks(connectivityManager, true)) {
            addRoutev6(net, false)
        }
    }

    private fun installRoutesExcluded(builder: Builder, routes: NetworkSpace) {
        for (included in routes.getNetworks(true)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.addRoute(included.prefix)
                }
            } catch (failure: Exception) {
                OpenVPNLogger.e("ManaOpenVpnService", "Failed to add route $included: ${failure.localizedMessage}")
            }
        }

        for (excluded in routes.getNetworks(false)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.excludeRoute(excluded.prefix)
                }
            } catch (failure: Exception) {
                OpenVPNLogger.e("ManaOpenVpnService", "Failed to exclude route $excluded: ${failure.localizedMessage}")
            }
        }
    }

    private fun installRoutesPositiveOnly(
            builder: Builder,
            positiveIPv4Routes: Collection<IpAddress>,
            positiveIPv6Routes: Collection<IpAddress>,
    ) {
        val multicastRange = IpAddress(CIDRIP("224.0.0.0", 3), true)

        for (route in positiveIPv4Routes) {
            try {
                if (!multicastRange.containsNet(route)) {
                    builder.addRoute(route.getIPv4Address(), route.networkMask)
                }
            } catch (failure: IllegalArgumentException) {
                OpenVPNLogger.e("ManaOpenVpnService", "Failed IPv4 route $route: ${failure.localizedMessage}")
            }
        }

        for (route in positiveIPv6Routes) {
            try {
                builder.addRoute(route.getIPv6Address(), route.networkMask)
            } catch (failure: IllegalArgumentException) {
                OpenVPNLogger.e("ManaOpenVpnService", "Failed IPv6 route $route: ${failure.localizedMessage}")
            }
        }
    }

    private fun endVpnService() {
        stopOpenVPN()
    }

    private var mtu: Int? = null
    private var domain: String? = null
    private var localIp: CIDRIP? = null
    private var localIPv6: String? = null
    private val dnsList: MutableList<String> = mutableListOf()
    private val routesV4 = NetworkSpace()
    private val routesV6 = NetworkSpace()

    override fun setMtu(mtu: Int) {
        this.mtu = mtu
    }

    override fun addDNS(dns: String?) {
        dns?.let { dnsList.add(it) }
    }

    override fun addRoute(route: CIDRIP?, include: Boolean) {
        routesV4.addIP(route, include)
    }

    override fun addRoute(dest: String?, mask: String?, gateway: String?, device: String?) {
        val route = CIDRIP(dest!!, maskToPrefixLength(mask!!))
        var include = isAndroidTunDevice(device)

        val gatewayIP = IpAddress(CIDRIP(gateway!!, 32), false)
        if (localIp == null) {
            OpenVPNLogger.e(
                    "ManaOpenVpnService",
                    "Local IP is not set; opening TUN may fail"
            )
            return
        }

        val localNet = IpAddress(localIp, true)
        if (localNet.containsNet(gatewayIP)) include = true
        if (gateway == "255.255.255.255") include = true

        if (route.normalise()) {
            OpenVPNLogger.e("ManaOpenVpnService", "Route normalized: $dest")
        }

        routesV4.addIP(route, include)
    }

    private fun maskToPrefixLength(mask: String): Int {
        return if (mask.contains('.')) {
            val octets = mask.split('.')
            if (octets.size != 4) {
                throw IllegalArgumentException("Invalid IPv4 netmask: $mask")
            }
            octets.sumOf { octet ->
                val value = octet.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid IPv4 netmask: $mask")
                if (value !in 0..255) {
                    throw IllegalArgumentException("Invalid IPv4 netmask: $mask")
                }
                Integer.bitCount(value)
            }
        } else {
            mask.toIntOrNull() ?: throw IllegalArgumentException("Invalid prefix length: $mask")
        }
    }

    override fun addRoutev6(network: String?, device: String?) {
        val included = isAndroidTunDevice(device)
        network?.let { addRoutev6(it, included) }
    }

    private fun addRoutev6(network: String, included: Boolean) {
        val parts = network.split("/").toTypedArray()
        try {
            val ip = InetAddress.getAllByName(parts[0])[0] as Inet6Address
            val mask = parts[1].toInt()
            routesV6.addIPv6(ip, mask, included)
        } catch (failure: UnknownHostException) {
            OpenVPNLogger.e("ManaOpenVpnService", "Failed IPv6 route parse: ${failure.localizedMessage}")
        }
    }

    override fun setDomain(domain: String?) {
        domain?.let { this.domain = it }
    }

    override fun addHttpProxy(proxy: String?, port: Int): Boolean {
        return false
    }

    override fun protectFd(fd: Int): Boolean {
        return protect(fd)
    }

    override fun openTun(): ParcelFileDescriptor? {
        return startTun()
    }

    override fun setLocalIP(cidrip: CIDRIP?) {
        localIp = cidrip
    }

    override fun setLocalIPv6(ipv6addr: String?) {
        localIPv6 = ipv6addr
    }

    override fun trigger_sso(info: String?) = Unit

    override val ctResolver: ContentResolver
        get() = contentResolver

    override val connectivityManager: ConnectivityManager
        get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun openvpnStopped() {
        Log.d(TAG, "openvpnStopped(): OpenVPN core reported stop")
        stopNotification()
        job?.cancel()
        job = null
        management = null
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    override fun updateStateThread(state: ConnectionState) {
        updateConnectionState(state)
    }

    private fun updateConnectionState(state: ConnectionState) {
        currentState = state
        updateState(state)
    }

    private fun isAndroidTunDevice(device: String?): Boolean {
        return device != null && (device.startsWith("tun") || device == "(null)" || device == VPNSERVICE_TUN)
    }

    private fun Intent.parseConfiguration(): OpenVPNConfig? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(CONFIGURATION_KEY, OpenVPNConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(CONFIGURATION_KEY)
        }
    }

    private fun Intent.parsePrivateKeyPassword(): String? {
        return getStringExtra(PRIVATE_KEY_PASSWORD_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }

    private fun Intent.parseUsername(): String? {
        return getStringExtra(USERNAME_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }

    private fun Intent.parsePassword(): String? {
        return getStringExtra(PASSWORD_KEY)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "ManaOpenVpnDiag"
        const val CONFIGURATION_KEY = "CONFIGURATION_KEY"
        const val USERNAME_KEY = "USERNAME_KEY"
        const val PASSWORD_KEY = "PASSWORD_KEY"
        const val PRIVATE_KEY_PASSWORD_KEY = "PRIVATE_KEY_PASSWORD_KEY"
        private const val VPN_SERVICE_ACTION = "android.net.VpnService"

        fun startService(
                context: Context,
                config: OpenVPNConfig,
                notificationClass: String? = null,
                allowedApplications: Array<String> = emptyArray(),
                username: String? = null,
                password: String? = null,
                privateKeyPassword: String? = null,
        ) {
            val intent = Intent(context, ManaOpenVpnService::class.java).apply {
                setPackage(context.applicationContext.packageName)
                putExtra(ACTION_KEY, ACTION_START_KEY)
                putExtra(CONFIGURATION_KEY, config)
                putExtra(NOTIFICATION_CLASS_KEY, notificationClass)
                putExtra(ALLOWED_APPS_KEY, allowedApplications)
                putExtra(USERNAME_KEY, username)
                putExtra(PASSWORD_KEY, password)
                putExtra(PRIVATE_KEY_PASSWORD_KEY, privateKeyPassword)
            }

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ManaOpenVpnService::class.java).apply {
                setPackage(context.applicationContext.packageName)
                putExtra(ACTION_KEY, ACTION_STOP_KEY)
            }
            context.startService(intent)
        }
    }
}
