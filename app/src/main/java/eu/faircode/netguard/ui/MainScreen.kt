package eu.faircode.netguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.faircode.netguard.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vpnEnabled: Boolean,
    onVpnToggle: (Boolean) -> Unit,
    appList: List<AppItemState>,
    onAppClick: (AppItemState) -> Unit,
    onWifiToggle: (AppItemState, Boolean) -> Unit,
    onMobileToggle: (AppItemState, Boolean) -> Unit
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { /* Search */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { /* More */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // VPN Status Card
            item {
                StatusCard(vpnEnabled, onVpnToggle)
            }

            // Stats Section
            item {
                StatsSection()
            }

            // App List Header
            item {
                Text(
                    text = "Applications",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // App Items
            items(appList) { app ->
                AppCard(
                    app = app,
                    onClick = { onAppClick(app) },
                    onWifiToggle = { onWifiToggle(app, it) },
                    onMobileToggle = { onMobileToggle(app, it) }
                )
            }
        }
    }
}

@Composable
fun StatusCard(vpnEnabled: Boolean, onVpnToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (vpnEnabled) MaterialTheme.colorScheme.primaryContainer 
                             else MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (vpnEnabled) "Protection Active" else "Protection Paused",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (vpnEnabled) "Your traffic is being filtered" else "Apps have unrestricted access",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Switch(
                checked = vpnEnabled,
                onCheckedChange = onVpnToggle,
                thumbContent = {
                    Icon(
                        imageVector = if (vpnEnabled) Icons.Default.Shield else Icons.Default.ShieldMoon,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                }
            )
        }
    }
}

@Composable
fun StatsSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatItem(
            modifier = Modifier.weight(1f),
            label = "Blocked",
            value = "1.2k",
            icon = Icons.Default.Block,
            color = MaterialTheme.colorScheme.error
        )
        StatItem(
            modifier = Modifier.weight(1f),
            label = "Sent",
            value = "45MB",
            icon = Icons.Default.ArrowUpward,
            color = MaterialTheme.colorScheme.primary
        )
        StatItem(
            modifier = Modifier.weight(1f),
            label = "Received",
            value = "128MB",
            icon = Icons.Default.ArrowDownward,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun StatItem(modifier: Modifier, label: String, value: String, icon: ImageVector, color: Color) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppCard(
    app: AppItemState,
    onClick: () -> Unit,
    onWifiToggle: (Boolean) -> Unit,
    onMobileToggle: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Icon Placeholder
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Android, contentDescription = null)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = app.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onWifiToggle(!app.wifiBlocked) }) {
                        Icon(
                            imageVector = if (app.wifiBlocked) Icons.Default.WifiOff else Icons.Default.Wifi,
                            contentDescription = "Wifi",
                            tint = if (app.wifiBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { onMobileToggle(!app.mobileBlocked) }) {
                        Icon(
                            imageVector = if (app.mobileBlocked) Icons.Default.SignalCellularConnectedNoInternet0Bar else Icons.Default.SignalCellular4Bar,
                            contentDescription = "Mobile",
                            tint = if (app.mobileBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 72.dp, end = 12.dp, bottom = 12.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("UID: ${app.uid}", style = MaterialTheme.typography.bodySmall)
                    Text("Total Data: ${app.totalData}", style = MaterialTheme.typography.bodySmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { /* Settings */ }) {
                            Text("Settings")
                        }
                        TextButton(onClick = { /* Launch */ }) {
                            Text("Launch")
                        }
                    }
                }
            }
        }
    }
}

data class AppItemState(
    val uid: Int,
    val name: String,
    val packageName: String,
    val wifiBlocked: Boolean,
    val mobileBlocked: Boolean,
    val totalData: String = "0 KB"
)

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MaterialTheme {
        MainScreen(
            vpnEnabled = true,
            onVpnToggle = {},
            appList = listOf(
                AppItemState(1, "WhatsApp", "com.whatsapp", wifiBlocked = false, mobileBlocked = true, totalData = "12 MB"),
                AppItemState(2, "Firefox", "org.mozilla.firefox", wifiBlocked = true, mobileBlocked = false, totalData = "450 KB"),
                AppItemState(3, "System UI", "com.android.systemui", wifiBlocked = false, mobileBlocked = false, totalData = "2 MB")
            ),
            onAppClick = {},
            onWifiToggle = { _, _ -> },
            onMobileToggle = { _, _ -> }
        )
    }
}
