package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
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

    private val peersState = mutableStateOf<List<WifiP2pDevice>>(emptyList())
    private val connectionState = mutableStateOf("Disconnected")
    private val wifiP2pEnabled = mutableStateOf(false)
    private val balanceState = mutableDoubleStateOf(1000.0)

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val peers by peersState
                val connectionInfo by connectionState
                val isWifiP2pEnabled by wifiP2pEnabled
                val balance by balanceState

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PaymentApp(
                        modifier = Modifier.padding(innerPadding),
                        peers = peers,
                        connectionStatus = connectionInfo,
                        isWifiP2pEnabled = isWifiP2pEnabled,
                        balance = balance,
                        onDiscoverPeers = { startDiscovery() },
                        onConnectToPeer = { device -> connectToPeer(device) },
                        onStartReceiving = { createGroup() },
                        onCheckPermissions = { checkAndRequestPermissions() },
                        onSendPayment = { amount, deviceName -> sendPayment(amount, deviceName) }
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
        serverSocket?.close()
        clientSocket?.close()
    }

    private fun checkAndRequestPermissions(): Boolean {
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
        if (!checkAndRequestPermissions()) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirect", "Discovery started")
                Toast.makeText(this@MainActivity, "Searching for nearby devices...", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reasonCode: Int) {
                val reason = when(reasonCode) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct not supported"
                    WifiP2pManager.BUSY -> "System busy, try again"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Error code: $reasonCode"
                }
                Log.e("WiFiDirect", "Discovery failed: $reason")
                Toast.makeText(this@MainActivity, "Discovery failed: $reason", Toast.LENGTH_LONG).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        if (!checkAndRequestPermissions()) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }

        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirect", "Initiating connection to ${device.deviceName}")
                Toast.makeText(this@MainActivity, "Connecting to ${device.deviceName}...", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Log.e("WiFiDirect", "Connection failed: $reason")
                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!checkAndRequestPermissions()) {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            return
        }

        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("WiFiDirect", "Group created - now discoverable")
                Toast.makeText(this@MainActivity, "You are now discoverable", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Log.e("WiFiDirect", "Group creation failed: $reason")
                Toast.makeText(this@MainActivity, "Failed to become discoverable", Toast.LENGTH_SHORT).show()
            }
        })
    }

    fun updatePeers(deviceList: Collection<WifiP2pDevice>) {
        peersState.value = deviceList.toList()
        Log.d("WiFiDirect", "Peers updated: ${deviceList.size} devices found")
    }

    fun updateConnectionInfo(info: String) {
        connectionState.value = info
        Log.d("WiFiDirect", "Connection status: $info")
    }

    fun setWifiP2pEnabled(enabled: Boolean) {
        wifiP2pEnabled.value = enabled
        Log.d("WiFiDirect", "Wi-Fi P2P enabled: $enabled")
    }

    fun handleConnectionInfo(info: WifiP2pInfo) {
        if (info.groupFormed) {
            if (info.isGroupOwner) {
                // Start server to receive payments
                startServer()
            } else {
                // Client - ready to send
                Log.d("WiFiDirect", "Connected to host: ${info.groupOwnerAddress}")
            }
        }
    }

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(8888)
                Log.d("WiFiDirect", "Server started on port 8888")

                while (isActive) {
                    try {
                        val client = serverSocket?.accept()
                        Log.d("WiFiDirect", "Client connected: ${client?.inetAddress}")

                        client?.let { socket ->
                            launch {
                                handleClientConnection(socket)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e("WiFiDirect", "Error accepting client: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WiFiDirect", "Server error: ${e.message}")
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val message = input.readLine()

            Log.d("WiFiDirect", "Received: $message")

            val json = JSONObject(message)
            val amount = json.getDouble("amount")
            val sender = json.getString("sender")

            withContext(Dispatchers.Main) {
                balanceState.doubleValue += amount
                Toast.makeText(
                    this@MainActivity,
                    "Received $${"%.2f".format(amount)} from $sender",
                    Toast.LENGTH_LONG
                ).show()
            }

            // Send acknowledgment
            val output = PrintWriter(socket.getOutputStream(), true)
            output.println("ACK")

            socket.close()
        } catch (e: Exception) {
            Log.e("WiFiDirect", "Error handling client: ${e.message}")
        }
    }

    private fun sendPayment(amount: Double, deviceName: String) {
        scope.launch {
            try {
                // Get connection info to get host address
                manager.requestConnectionInfo(channel) { info ->
                    if (info?.groupFormed == true && !info.isGroupOwner) {
                        scope.launch {
                            sendPaymentToHost(info.groupOwnerAddress, amount, deviceName)
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Not connected to a receiver",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WiFiDirect", "Error sending payment: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Payment failed: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private suspend fun sendPaymentToHost(hostAddress: InetAddress, amount: Double, deviceName: String) {
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(hostAddress, 8888), 5000)

            Log.d("WiFiDirect", "Connected to server: $hostAddress")

            val json = JSONObject().apply {
                put("amount", amount)
                put("sender", Build.MODEL) // or get device name
            }

            val output = PrintWriter(socket.getOutputStream(), true)
            output.println(json.toString())

            // Wait for acknowledgment
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val response = input.readLine()

            Log.d("WiFiDirect", "Server response: $response")

            withContext(Dispatchers.Main) {
                balanceState.doubleValue -= amount
                Toast.makeText(
                    this@MainActivity,
                    "Sent $${"%.2f".format(amount)} to $deviceName",
                    Toast.LENGTH_LONG
                ).show()
            }

            socket.close()
        } catch (e: Exception) {
            Log.e("WiFiDirect", "Error sending to host: ${e.message}")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Payment failed: ${e.message}",
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
                activity.setWifiP2pEnabled(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peerList ->
                    activity.updatePeers(peerList.deviceList)
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                manager.requestConnectionInfo(channel) { info ->
                    val status = when {
                        info == null -> "Disconnected"
                        info.groupFormed && info.isGroupOwner -> "Connected as Host"
                        info.groupFormed -> "Connected as Client"
                        else -> "Disconnected"
                    }
                    activity.updateConnectionInfo(status)

                    // Handle connection for socket communication
                    if (info?.groupFormed == true) {
                        activity.handleConnectionInfo(info)
                    }
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                // Device info changed
            }
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
    onDiscoverPeers: () -> Unit,
    onConnectToPeer: (WifiP2pDevice) -> Unit,
    onStartReceiving: () -> Unit,
    onCheckPermissions: () -> Boolean,
    onSendPayment: (Double, String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var selectedDevice by remember { mutableStateOf<WifiP2pDevice?>(null) }

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Wi-Fi P2P Status Warning
        if (!isWifiP2pEnabled) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, "Warning", tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Wi-Fi Direct is disabled. Please enable Wi-Fi.",
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // Balance Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Your Balance", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$${"%.2f".format(balance)}",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                Text("Status: $connectionStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

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
            0 -> {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount to Send") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Text("$") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (onCheckPermissions()) {
                            onDiscoverPeers()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isWifiP2pEnabled
                ) {
                    Icon(Icons.Default.Search, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search for Recipients")
                }

                Text(
                    "Nearby People (${peers.size}):",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp)
                )

                if (peers.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    ) {
                        Text(
                            "No devices found. Make sure the other device is discoverable.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { device ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    selectedDevice = device
                                    onConnectToPeer(device)
                                }
                        ) {
                            ListItem(
                                headlineContent = { Text(device.deviceName) },
                                supportingContent = {
                                    Text(
                                        if (selectedDevice?.deviceAddress == device.deviceAddress &&
                                            connectionStatus.contains("Connected"))
                                            "Connected - Ready to send"
                                        else
                                            "Tap to connect"
                                    )
                                },
                                trailingContent = {
                                    if (selectedDevice?.deviceAddress == device.deviceAddress &&
                                        connectionStatus.contains("Client")) {
                                        Button(onClick = {
                                            val amt = amount.toDoubleOrNull() ?: 0.0
                                            if (amt <= 0) {
                                                Toast.makeText(context, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                                            } else if (amt > balance) {
                                                Toast.makeText(context, "Insufficient balance", Toast.LENGTH_SHORT).show()
                                            } else {
                                                onSendPayment(amt, device.deviceName)
                                                amount = ""
                                            }
                                        }) {
                                            Text("Pay")
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            1 -> {
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
                        "Others can find you to send money",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            if (onCheckPermissions()) {
                                onStartReceiving()
                            }
                        },
                        enabled = isWifiP2pEnabled
                    ) {
                        Text("Make Me Discoverable")
                    }

                    if (connectionStatus.contains("Host")) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Ready to receive payments!",
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Server is running on port 8888",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Text(
                                    "Waiting for incoming payments...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}