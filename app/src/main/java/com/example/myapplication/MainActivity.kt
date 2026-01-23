package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "NetFreePay"
private const val PORT = 8888
private const val CONNECTION_TIMEOUT = 10000
private const val SOCKET_TIMEOUT = 8000

data class IncomingPayment(
    val amount: Double,
    val sender: String,
    val onResponse: (Boolean) -> Unit
)

class MainActivity : ComponentActivity() {
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiver: BroadcastReceiver? = null

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private var peers by mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private var connectionStatus by mutableStateOf("Disconnected")
    private var isWifiP2pEnabled by mutableStateOf(false)
    private var balance by mutableDoubleStateOf(1000.0)
    private var incomingPayment by mutableStateOf<IncomingPayment?>(null)
    private var connectedDeviceAddress: String? = null
    private var isGroupOwner by mutableStateOf(false)

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PaymentApp(
                        modifier = Modifier.padding(innerPadding),
                        peers = peers,
                        connectionStatus = connectionStatus,
                        isWifiP2pEnabled = isWifiP2pEnabled,
                        balance = balance,
                        incomingPayment = incomingPayment,
                        onDiscoverPeers = ::startDiscovery,
                        onConnectToPeer = ::connectToPeer,
                        onStartReceiving = ::createGroup,
                        onCheckPermissions = ::checkAndRequestPermissions,
                        onSendPayment = ::sendPayment,
                        onDisconnect = ::disconnect
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        receiver = WiFiDirectBroadcastReceiver(manager, channel, this)
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        receiver?.let { unregisterReceiver(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        closeSockets()
    }

    private fun closeSockets() {
        try {
            serverJob?.cancel()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun checkAndRequestPermissions(): Boolean {
        // Check location services
        if (!isLocationEnabled()) {
            Toast.makeText(
                this,
                "Please enable Location Services in Settings",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return false
        }

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!allGranted) {
            requestPermissions(permissions, 1001)
        }
        return allGranted
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!checkAndRequestPermissions()) return

        Log.d(TAG, "Starting peer discovery")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Searching for devices...", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                val errorMsg = when(reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "WiFi Direct not supported on this device"
                    WifiP2pManager.BUSY -> "System is busy, please try again"
                    WifiP2pManager.ERROR -> "Internal error occurred"
                    else -> "Unknown error: $reason"
                }
                Log.e(TAG, "Discovery failed: $errorMsg (code: $reason)")
                Toast.makeText(this@MainActivity, "Discovery failed: $errorMsg", Toast.LENGTH_LONG).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        if (!checkAndRequestPermissions()) return

        Log.d(TAG, "Initiating connection to: ${device.deviceName} (${device.deviceAddress})")
        connectionStatus = "Connecting..."

        // Stop discovery first to improve connection stability
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery stopped")
                cleanupAndConnect(device)
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Failed to stop discovery, proceeding anyway")
                cleanupAndConnect(device)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun cleanupAndConnect(device: WifiP2pDevice) {
        // Remove any existing group before connecting
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Previous group removed")
                performConnect(device)
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "No previous group to remove")
                performConnect(device)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun performConnect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            // Set to 1 to prefer being group owner (helps with connection stability)
            groupOwnerIntent = 1
        }

        Log.d(TAG, "Connecting with config: ${config.deviceAddress}, GO Intent: ${config.groupOwnerIntent}")

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection request sent successfully")
                Toast.makeText(this@MainActivity, "Connecting to ${device.deviceName}...", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                val errorMsg = when(reason) {
                    WifiP2pManager.BUSY -> "System busy, try again"
                    WifiP2pManager.ERROR -> "Connection error"
                    else -> "Failed (code: $reason)"
                }
                Log.e(TAG, "Connection failed: $errorMsg")
                connectionStatus = "Disconnected"
                Toast.makeText(this@MainActivity, "Connection failed: $errorMsg", Toast.LENGTH_LONG).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!checkAndRequestPermissions()) return

        Log.d(TAG, "Creating WiFi Direct group")
        connectionStatus = "Creating group..."

        // Clean up any existing group first
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Removed existing group")
                performCreateGroup()
            }
            override fun onFailure(reason: Int) {
                Log.d(TAG, "No existing group to remove")
                performCreateGroup()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun performCreateGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group created successfully")
                Toast.makeText(
                    this@MainActivity,
                    "Ready to receive payments",
                    Toast.LENGTH_SHORT
                ).show()
            }
            override fun onFailure(reason: Int) {
                val errorMsg = when(reason) {
                    WifiP2pManager.BUSY -> "System busy, try again"
                    WifiP2pManager.ERROR -> "Failed to create group"
                    else -> "Error code: $reason"
                }
                Log.e(TAG, "Group creation failed: $errorMsg")
                connectionStatus = "Disconnected"
                Toast.makeText(
                    this@MainActivity,
                    "Failed to create group: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        Log.d(TAG, "Disconnecting from WiFi Direct")
        closeSockets()

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Disconnected successfully")
                connectionStatus = "Disconnected"
                connectedDeviceAddress = null
                isGroupOwner = false
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Disconnect failed: $reason")
                // Force update status anyway
                connectionStatus = "Disconnected"
                connectedDeviceAddress = null
                isGroupOwner = false
            }
        })
    }

    fun updatePeers(deviceList: Collection<WifiP2pDevice>) {
        Log.d(TAG, "Peers updated: ${deviceList.size} devices found")
        peers = deviceList.toList()
    }

    fun updateConnectionInfo(info: String) {
        Log.d(TAG, "Connection status: $info")
        connectionStatus = info
    }

    fun setIsWifiP2pEnabled(enabled: Boolean) {
        Log.d(TAG, "WiFi P2P enabled: $enabled")
        isWifiP2pEnabled = enabled
    }

    fun handleConnectionInfo(info: WifiP2pInfo) {
        Log.d(TAG, """
            Connection Info:
            - Group Formed: ${info.groupFormed}
            - Is Group Owner: ${info.isGroupOwner}
            - Group Owner Address: ${info.groupOwnerAddress?.hostAddress}
        """.trimIndent())

        if (info.groupFormed) {
            isGroupOwner = info.isGroupOwner

            if (info.isGroupOwner) {
                // This device is the server/receiver
                Log.d(TAG, "Starting server as group owner")
                // Add delay to ensure WiFi Direct connection is stable
                scope.launch {
                    delay(2000) // Wait 2 seconds for connection to stabilize
                    startServer()
                }
            } else {
                // This device is the client/sender
                connectedDeviceAddress = info.groupOwnerAddress?.hostAddress
                Log.d(TAG, "Connected as client to: $connectedDeviceAddress")
                // Connection is ready for sending payments
            }
        }
    }

    private fun startServer() {
        if (serverJob?.isActive == true) {
            Log.d(TAG, "Server already running")
            return
        }

        serverJob = scope.launch {
            try {
                // Close any existing socket
                serverSocket?.close()

                // Create new server socket
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = CONNECTION_TIMEOUT
                    bind(InetSocketAddress(PORT))
                }

                Log.d(TAG, "Server started successfully on port $PORT")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ready to receive payments",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Accept client connections
                while (isActive) {
                    try {
                        val client = serverSocket?.accept()
                        if (client != null) {
                            Log.d(TAG, "Client connected from: ${client.inetAddress.hostAddress}")
                            launch { handleClientConnection(client) }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error accepting client: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Server error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = SOCKET_TIMEOUT

                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val message = input.readLine()

                if (message == null) {
                    Log.e(TAG, "Received null message from client")
                    return@use
                }

                Log.d(TAG, "Received payment request: $message")

                val json = JSONObject(message)
                val amount = json.getDouble("amount")
                val sender = json.getString("sender")

                // Wait for user to accept/reject
                val completer = CompletableDeferred<Boolean>()

                withContext(Dispatchers.Main) {
                    incomingPayment = IncomingPayment(amount, sender) { accepted ->
                        completer.complete(accepted)
                    }
                }

                val accepted = completer.await()

                val output = PrintWriter(s.getOutputStream(), true)

                if (accepted) {
                    withContext(Dispatchers.Main) {
                        balance += amount
                        incomingPayment = null
                        Toast.makeText(
                            this@MainActivity,
                            "Received $${"%.2f".format(amount)} from $sender",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    output.println("ACK")
                    Log.d(TAG, "Payment accepted and confirmed")
                } else {
                    withContext(Dispatchers.Main) {
                        incomingPayment = null
                        Toast.makeText(
                            this@MainActivity,
                            "Payment rejected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    output.println("REJECTED")
                    Log.d(TAG, "Payment rejected")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection: ${e.message}", e)
        }
    }

    private fun sendPayment(amount: Double, deviceName: String) {
        Log.d(TAG, "Preparing to send payment: $$amount")

        manager.requestConnectionInfo(channel) { info ->
            if (info?.groupFormed == true && !info.isGroupOwner) {
                if (info.groupOwnerAddress != null &&
                    info.groupOwnerAddress.hostAddress != null &&
                    info.groupOwnerAddress.hostAddress != "0.0.0.0") {

                    Log.d(TAG, "Sending payment to ${info.groupOwnerAddress.hostAddress}")
                    scope.launch {
                        // Small delay to ensure connection is stable
                        delay(500)
                        sendPaymentToHost(info.groupOwnerAddress, amount, deviceName)
                    }
                } else {
                    Log.e(TAG, "Group owner address not ready")
                    Toast.makeText(
                        this,
                        "Connection not ready, please wait",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.e(TAG, "Not connected as client. GroupFormed: ${info?.groupFormed}, IsOwner: ${info?.isGroupOwner}")
                Toast.makeText(
                    this,
                    "Please connect to a receiver first",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private suspend fun sendPaymentToHost(
        hostAddress: InetAddress?,
        amount: Double,
        deviceName: String
    ) {
        if (hostAddress == null || hostAddress.hostAddress == "0.0.0.0") {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Invalid host address",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        Log.d(TAG, "Connecting to ${hostAddress.hostAddress}:$PORT")

        try {
            Socket().use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT
                socket.connect(InetSocketAddress(hostAddress, PORT), CONNECTION_TIMEOUT)

                Log.d(TAG, "Socket connected successfully")

                val json = JSONObject().apply {
                    put("amount", amount)
                    put("sender", Build.MODEL)
                }

                val output = PrintWriter(socket.getOutputStream(), true)
                output.println(json.toString())

                Log.d(TAG, "Payment request sent, waiting for response")

                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = input.readLine()

                Log.d(TAG, "Received response: $response")

                withContext(Dispatchers.Main) {
                    when (response) {
                        "ACK" -> {
                            balance -= amount
                            Toast.makeText(
                                this@MainActivity,
                                "Sent $${"%.2f".format(amount)} to $deviceName",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        "REJECTED" -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Payment rejected by recipient",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> {
                            Toast.makeText(
                                this@MainActivity,
                                "Unexpected response: $response",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Payment send error: ${e.message}", e)
            withContext(Dispatchers.Main) {
                val errorMsg = when {
                    e.message?.contains("ETIMEDOUT") == true -> "Connection timeout"
                    e.message?.contains("ECONNREFUSED") == true -> "Connection refused"
                    else -> e.message ?: "Unknown error"
                }
                Toast.makeText(
                    this@MainActivity,
                    "Failed to send: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val activity: MainActivity
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "WiFi P2P state changed: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                activity.setIsWifiP2pEnabled(isEnabled)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Peers list changed")
                // Check permissions before requesting peers
                if (hasRequiredPermissions(context)) {
                    manager.requestPeers(channel) { peerList ->
                        activity.updatePeers(peerList.deviceList)
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "Connection state changed")

                if (hasRequiredPermissions(context)) {
                    manager.requestConnectionInfo(channel) { info ->
                        if (info == null) {
                            Log.w(TAG, "Connection info is null")
                            activity.updateConnectionInfo("Disconnected")
                            return@requestConnectionInfo
                        }

                        Log.d(TAG, """
                            Connection Changed:
                            - Group Formed: ${info.groupFormed}
                            - Is Group Owner: ${info.isGroupOwner}
                            - Owner Address: ${info.groupOwnerAddress?.hostAddress}
                        """.trimIndent())

                        val status = when {
                            !info.groupFormed -> "Disconnected"
                            info.isGroupOwner -> "Connected (Receiving)"
                            else -> "Connected (Sending)"
                        }

                        activity.updateConnectionInfo(status)

                        if (info.groupFormed) {
                            activity.handleConnectionInfo(info)
                        }
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                Log.d(TAG, "This device changed")
            }
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun PaymentApp(
    modifier: Modifier = Modifier,
    peers: List<WifiP2pDevice>,
    connectionStatus: String,
    isWifiP2pEnabled: Boolean,
    balance: Double,
    incomingPayment: IncomingPayment?,
    onDiscoverPeers: () -> Unit,
    onConnectToPeer: (WifiP2pDevice) -> Unit,
    onStartReceiving: () -> Unit,
    onCheckPermissions: () -> Boolean,
    onSendPayment: (Double, String) -> Unit,
    onDisconnect: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<WifiP2pDevice?>(null) }

    if (incomingPayment != null) {
        AlertDialog(
            onDismissRequest = { incomingPayment.onResponse(false) },
            title = { Text("Incoming Payment") },
            text = {
                Text("Accept $${"%.2f".format(incomingPayment.amount)} from ${incomingPayment.sender}?")
            },
            confirmButton = {
                Button(onClick = { incomingPayment.onResponse(true) }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { incomingPayment.onResponse(false) }) {
                    Text("Decline")
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!isWifiP2pEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Wi-Fi Direct is disabled. Please enable it in Settings.",
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        BalanceCard(balance, connectionStatus, onDisconnect)

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Send") },
                icon = { Icon(Icons.AutoMirrored.Filled.Send, null) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Receive") },
                icon = { Icon(Icons.Default.AccountBalanceWallet, null) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> SendTab(
                amount,
                { amount = it },
                peers,
                connectionStatus,
                selectedDevice,
                balance,
                onCheckPermissions,
                onDiscoverPeers,
                { device ->
                    selectedDevice = device
                    onConnectToPeer(device)
                },
                { amt, name ->
                    onSendPayment(amt, name)
                    amount = ""
                }
            )
            1 -> ReceiveTab(
                connectionStatus,
                peers,
                onCheckPermissions,
                onStartReceiving
            )
        }
    }
}

@Composable
fun BalanceCard(balance: Double, status: String, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Your Balance", style = MaterialTheme.typography.titleMedium)
            Text(
                "${"%.2f".format(balance)}",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    when {
                        status.contains("Receiving") -> Icons.Default.CloudDownload
                        status.contains("Sending") -> Icons.Default.CloudUpload
                        else -> Icons.Default.CloudOff
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium)
            }
            if (status != "Disconnected") {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onDisconnect) {
                    Text("Disconnect", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun SendTab(
    amount: String,
    onAmountChange: (String) -> Unit,
    peers: List<WifiP2pDevice>,
    status: String,
    selectedDevice: WifiP2pDevice?,
    balance: Double,
    onCheck: () -> Boolean,
    onDiscover: () -> Unit,
    onConnect: (WifiP2pDevice) -> Unit,
    onPay: (Double, String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = amount,
            onValueChange = { newValue ->
                // Only allow valid decimal numbers
                if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                    onAmountChange(newValue)
                }
            },
            label = { Text("Amount to Send") },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Text("$") },
            supportingText = { Text("Available: ${"%.2f".format(balance)}") },
            isError = amount.toDoubleOrNull()?.let { it > balance || it <= 0 } ?: false
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { if (onCheck()) onDiscover() },
            modifier = Modifier.fillMaxWidth(),
            enabled = status == "Disconnected"
        ) {
            Icon(Icons.Default.Search, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search for Receivers")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Nearby Receivers (${peers.size}):",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (peers.isEmpty() && status == "Disconnected") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No receivers found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Make sure the other device is in 'Receive' mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(peers) { device ->
                val isConnected = status.contains("Sending") &&
                        (selectedDevice?.deviceAddress == device.deviceAddress)
                DeviceItem(
                    device = device,
                    isConnected = isConnected,
                    onConnect = { onConnect(device) }
                ) {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0 && amt <= balance) {
                        onPay(amt, device.deviceName)
                    }
                }
            }
        }
    }
}

@Composable
fun ReceiveTab(
    status: String,
    peers: List<WifiP2pDevice>,
    onCheck: () -> Boolean,
    onReceive: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(16.dp)
    ) {
        Icon(
            Icons.Default.Wifi,
            null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            if (status.contains("Receiving")) {
                "Ready to receive payments"
            } else {
                "Make yourself discoverable to receive payments"
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { if (onCheck()) onReceive() },
            enabled = status == "Disconnected"
        ) {
            Icon(Icons.Default.Visibility, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Make Discoverable")
        }

        if (status.contains("Receiving")) {
            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "You're discoverable!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Others can now find and send you payments",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (peers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Connected Senders (${peers.size}):",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.Start)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { device ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            ListItem(
                                headlineContent = { Text(device.deviceName) },
                                supportingContent = { Text("Connected") },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: WifiP2pDevice,
    isConnected: Boolean,
    onConnect: () -> Unit,
    onPay: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = !isConnected, onClick = onConnect)
    ) {
        ListItem(
            headlineContent = { Text(device.deviceName) },
            supportingContent = {
                Text(
                    if (isConnected) "Connected - Ready to send"
                    else "Tap to connect"
                )
            },
            leadingContent = {
                Icon(
                    if (isConnected) Icons.Default.Link else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (isConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            },
            trailingContent = {
                if (isConnected) {
                    Button(onClick = onPay) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Send")
                    }
                }
            }
        )
    }
}