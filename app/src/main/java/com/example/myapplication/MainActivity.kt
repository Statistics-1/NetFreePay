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
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.example.myapplication.ui.theme.MyApplicationTheme

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                var peers by remember { mutableStateOf<List<WifiP2pDevice>>(emptyList()) }
                var connectionInfo by remember { mutableStateOf<String>("Disconnected") }

                // Register receiver
                DisposableEffect(Unit) {
                    receiver = object : BroadcastReceiver() {
                        @SuppressLint("MissingPermission")
                        override fun onReceive(context: Context, intent: Intent) {
                            when (intent.action) {
                                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                                    manager.requestPeers(channel) { peerList ->
                                        peers = peerList.deviceList.toList()
                                    }
                                }
                                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                                    manager.requestConnectionInfo(channel) { info ->
                                        connectionInfo = if (info.groupFormed) {
                                            if (info.isGroupOwner) "Connected as Host" else "Connected as Client"
                                        } else {
                                            "Disconnected"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    registerReceiver(receiver, intentFilter)
                    onDispose {
                        unregisterReceiver(receiver)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PaymentApp(
                        modifier = Modifier.padding(innerPadding),
                        peers = peers,
                        connectionStatus = connectionInfo,
                        onDiscoverPeers = { startDiscovery() },
                        onConnectToPeer = { device -> connectToPeer(device) },
                        onStartReceiving = { createGroup() }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Discovery Started", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reasonCode: Int) {
                Toast.makeText(this@MainActivity, "Discovery Failed: $reasonCode", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Connecting to ${device.deviceName}", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Connect Failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Toast.makeText(this@MainActivity, "Ready to receive", Toast.LENGTH_SHORT).show()
            }
            override fun onFailure(reason: Int) {
                Toast.makeText(this@MainActivity, "Failed to start receiving", Toast.LENGTH_SHORT).show()
            }
        })
    }
}

@Composable
fun PaymentApp(
    modifier: Modifier = Modifier,
    peers: List<WifiP2pDevice>,
    connectionStatus: String,
    onDiscoverPeers: () -> Unit,
    onConnectToPeer: (WifiP2pDevice) -> Unit,
    onStartReceiving: () -> Unit
) {
    var balance by remember { mutableDoubleStateOf(1000.0) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var amount by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<WifiP2pDevice?>(null) }

    val context = LocalContext.current
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            if (isScanning) onDiscoverPeers() else onStartReceiving()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Balance Card
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your Balance", style = MaterialTheme.typography.titleMedium)
                Text("$${"%.2f".format(balance)}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                Text("Status: $connectionStatus", style = MaterialTheme.typography.bodySmall)
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Send") }, icon = { Icon(Icons.AutoMirrored.Filled.Send, null) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Receive") }, icon = { Icon(Icons.Default.AccountBalanceWallet, null) })
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
                        isScanning = true
                        launcher.launch(permissionsToRequest)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Search for Recipients")
                }
                
                Text("Nearby People:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(peers) { device ->
                        ListItem(
                            headlineContent = { Text(device.deviceName) },
                            supportingContent = { Text(device.deviceAddress) },
                            modifier = Modifier.clickable { 
                                selectedDevice = device
                                onConnectToPeer(device)
                            },
                            trailingContent = {
                                if (selectedDevice?.deviceAddress == device.deviceAddress && connectionStatus.contains("Connected")) {
                                    Button(onClick = {
                                        val amt = amount.toDoubleOrNull() ?: 0.0
                                        if (amt > 0 && amt <= balance) {
                                            balance -= amt
                                            amount = ""
                                            Toast.makeText(context, "Sent $amt to ${device.deviceName}", Toast.LENGTH_LONG).show()
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
            1 -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(100.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Others can find you to send money", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { 
                        isScanning = false
                        launcher.launch(permissionsToRequest)
                    }) {
                        Text("Make Me Discoverable")
                    }
                    
                    if (connectionStatus.contains("Connected")) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text("You are ready to receive!", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        // Mock receipt
                        Button(onClick = { balance += 50.0 }, modifier = Modifier.padding(top = 8.dp)) {
                            Text("Simulate Receipt (+$50)")
                        }
                    }
                }
            }
        }
    }
}
