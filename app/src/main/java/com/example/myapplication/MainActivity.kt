package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
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
        try { serverSocket?.close() } catch (e: Exception) { Log.e(TAG, "Close error", e) }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allGranted) requestPermissions(permissions, 1001)
        return allGranted
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        if (!checkAndRequestPermissions()) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Toast.makeText(this@MainActivity, "Searching...", Toast.LENGTH_SHORT).show() }
            override fun onFailure(reason: Int) { 
                val errorMsg = when(reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
                    WifiP2pManager.BUSY -> "System Busy"
                    WifiP2pManager.ERROR -> "Internal Error"
                    else -> "Unknown Error: $reason"
                }
                Toast.makeText(this@MainActivity, "Discovery failed: $errorMsg", Toast.LENGTH_SHORT).show() 
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        if (!checkAndRequestPermissions()) return
        Log.d(TAG, "Attempting to connect to: ${device.deviceName}")
        
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { proceedWithConnect(device) }
            override fun onFailure(reason: Int) { proceedWithConnect(device) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun proceedWithConnect(device: WifiP2pDevice) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { performConnect(device) }
            override fun onFailure(reason: Int) { performConnect(device) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun performConnect(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Connecting...", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Connect failed: $reason", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!checkAndRequestPermissions()) return
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { performCreateGroup() }
            override fun onFailure(reason: Int) { performCreateGroup() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun performCreateGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Discoverable as Group Owner", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) { Toast.makeText(this@MainActivity, "Failed to create group: $reason", Toast.LENGTH_SHORT).show() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun disconnect() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { connectionStatus = "Disconnected"; connectedDeviceAddress = null }
            override fun onFailure(reason: Int) { Log.e(TAG, "Disconnect failed: $reason") }
        })
        closeSockets()
        serverJob?.cancel()
    }

    fun updatePeers(deviceList: Collection<WifiP2pDevice>) { peers = deviceList.toList() }
    fun updateConnectionInfo(info: String) { connectionStatus = info }
    fun setIsWifiP2pEnabled(enabled: Boolean) { isWifiP2pEnabled = enabled }

    fun handleConnectionInfo(info: WifiP2pInfo) {
        if (info.groupFormed && info.isGroupOwner) startServer()
        else if (info.groupFormed) connectedDeviceAddress = info.groupOwnerAddress?.hostAddress
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
                Log.d(TAG, "Server started on port $PORT")
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    Log.d(TAG, "Client connected: ${client.inetAddress.hostAddress}")
                    launch { handleClientConnection(client) }
                }
            } catch (e: Exception) { 
                Log.e(TAG, "Server error: ${e.message}", e)
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Server Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        try {
            socket.use { s ->
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val message = input.readLine() ?: return@use
                val json = JSONObject(message)
                val amount = json.getDouble("amount")
                val sender = json.getString("sender")
                val completer = CompletableDeferred<Boolean>()
                withContext(Dispatchers.Main) {
                    incomingPayment = IncomingPayment(amount, sender) { accepted -> completer.complete(accepted) }
                }
                val accepted = completer.await()
                val output = PrintWriter(s.getOutputStream(), true)
                if (accepted) {
                    withContext(Dispatchers.Main) { balance += amount; incomingPayment = null; Toast.makeText(this@MainActivity, "Accepted", Toast.LENGTH_SHORT).show() }
                    output.println("ACK")
                } else {
                    withContext(Dispatchers.Main) { incomingPayment = null; Toast.makeText(this@MainActivity, "Rejected", Toast.LENGTH_SHORT).show() }
                    output.println("REJECTED")
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Client handle error", e) }
    }

    private fun sendPayment(amount: Double, deviceName: String) {
        manager.requestConnectionInfo(channel) { info ->
            if (info?.groupFormed == true && !info.isGroupOwner) {
                scope.launch { sendPaymentToHost(info.groupOwnerAddress, amount, deviceName) }
            } else Toast.makeText(this, "Not connected as sender", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun sendPaymentToHost(hostAddress: InetAddress?, amount: Double, deviceName: String) {
        if (hostAddress == null || hostAddress.hostAddress == "0.0.0.0") {
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Error: Host IP not ready", Toast.LENGTH_SHORT).show() }
            return
        }
        
        Log.d(TAG, "Attempting to send payment to ${hostAddress.hostAddress}")
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(hostAddress, PORT), 8000)
                val json = JSONObject().apply { 
                    put("amount", amount)
                    put("sender", Build.MODEL) 
                }
                
                val output = PrintWriter(socket.getOutputStream(), true)
                output.println(json.toString())
                Log.d(TAG, "Message sent, waiting for ACK...")

                val input = BufferedReader(InputStreamReader(socket.getInputStream()))
                val response = input.readLine()
                Log.d(TAG, "Received response: $response")

                withContext(Dispatchers.Main) {
                    when (response) {
                        "ACK" -> {
                            balance -= amount
                            Toast.makeText(this@MainActivity, "Sent $${"%.2f".format(amount)} to $deviceName", Toast.LENGTH_LONG).show()
                        }
                        "REJECTED" -> Toast.makeText(this@MainActivity, "Rejected by recipient", Toast.LENGTH_LONG).show()
                        else -> Toast.makeText(this@MainActivity, "Error: Unexpected response ($response)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Payment send error", e)
            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Connection Failed: ${e.message}", Toast.LENGTH_LONG).show() } 
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
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> activity.setIsWifiP2pEnabled(intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> manager.requestPeers(channel) { peerList -> activity.updatePeers(peerList.deviceList) }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                manager.requestConnectionInfo(channel) { info ->
                    Log.d("NetFreePay", "Connection Changed: groupFormed=${info?.groupFormed}, isOwner=${info?.isGroupOwner}")
                    val status = when {
                        info == null || !info.groupFormed -> "Disconnected"
                        info.isGroupOwner -> "Connected as Host"
                        else -> "Connected as Client"
                    }
                    activity.updateConnectionInfo(status)
                    if (info?.groupFormed == true) {
                        Log.d("NetFreePay", "Connected! Host IP: ${info.groupOwnerAddress?.hostAddress}")
                        activity.handleConnectionInfo(info)
                    }
                }
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
            text = { Text("Accept $${"%.2f".format(incomingPayment.amount)} from ${incomingPayment.sender}?") },
            confirmButton = { Button(onClick = { incomingPayment.onResponse(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { incomingPayment.onResponse(false) }) { Text("Decline") } }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!isWifiP2pEnabled) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Wi-Fi Direct disabled", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        BalanceCard(balance, connectionStatus, onDisconnect)

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Send") }, icon = { Icon(Icons.AutoMirrored.Filled.Send, null) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Receive") }, icon = { Icon(Icons.Default.AccountBalanceWallet, null) })
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            0 -> SendTab(amount, { amount = it }, peers, connectionStatus, selectedDevice, balance, onCheckPermissions, onDiscoverPeers, { selectedDevice = it; onConnectToPeer(it) }, { amt, name -> onSendPayment(amt, name); amount = "" })
            1 -> ReceiveTab(connectionStatus, peers, onCheckPermissions, onStartReceiving)
        }
    }
}

@Composable
fun BalanceCard(balance: Double, status: String, onDisconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Your Balance", style = MaterialTheme.typography.titleMedium)
            Text("$${"%.2f".format(balance)}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("Status: $status", style = MaterialTheme.typography.bodySmall)
            if (status != "Disconnected") {
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun SendTab(amount: String, onAmountChange: (String) -> Unit, peers: List<WifiP2pDevice>, status: String, selectedDevice: WifiP2pDevice?, balance: Double, onCheck: () -> Boolean, onDiscover: () -> Unit, onConnect: (WifiP2pDevice) -> Unit, onPay: (Double, String) -> Unit) {
    Column {
        OutlinedTextField(value = amount, onValueChange = onAmountChange, label = { Text("Amount") }, modifier = Modifier.fillMaxWidth(), leadingIcon = { Text("$") })
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { if (onCheck()) onDiscover() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Search, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search")
        }
        Text("Nearby People (${peers.size}):", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(peers) { device ->
                val isConnected = status.contains("Client") && (selectedDevice?.deviceAddress == device.deviceAddress)
                DeviceItem(device, isConnected, { onConnect(device) }) {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0 && amt <= balance) onPay(amt, device.deviceName)
                }
            }
        }
    }
}

@Composable
fun ReceiveTab(status: String, peers: List<WifiP2pDevice>, onCheck: () -> Boolean, onReceive: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Icon(Icons.Default.Wifi, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Wait for someone to send money", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = { if (onCheck()) onReceive() }) { Text("Make Discoverable") }
        if (status.contains("Host")) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Nearby People:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(peers) { device -> Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { ListItem(headlineContent = { Text(device.deviceName) }, supportingContent = { Text("Waiting...") }) } }
            }
        }
    }
}

@Composable
fun DeviceItem(device: WifiP2pDevice, isConnected: Boolean, onConnect: () -> Unit, onPay: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onConnect)) {
        ListItem(
            headlineContent = { Text(device.deviceName) },
            supportingContent = { Text(if (isConnected) "Connected" else "Tap to connect") },
            trailingContent = { if (isConnected) Button(onClick = onPay) { Text("Pay") } }
        )
    }
}
