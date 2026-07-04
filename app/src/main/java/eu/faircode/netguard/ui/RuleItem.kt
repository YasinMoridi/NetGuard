package eu.faircode.netguard.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.faircode.netguard.R

@Composable
fun RuleItem(
    name: String,
    packageName: String,
    uid: Int,
    version: String,
    wifiBlocked: Boolean,
    otherBlocked: Boolean,
    onWifiToggle: (Boolean) -> Unit,
    onOtherToggle: (Boolean) -> Unit,
    onSettingsClick: () -> Unit = {},
    onLaunchClick: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            // App Icon Placeholder
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )

            Text(
                text = name,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onWifiToggle(!wifiBlocked) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.wifi),
                        contentDescription = stringResource(id = R.string.title_block_wifi),
                        tint = if (wifiBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { onOtherToggle(!otherBlocked) }) {
                    Icon(
                        painter = painterResource(id = R.drawable.other),
                        contentDescription = stringResource(id = R.string.title_block_other),
                        tint = if (otherBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 32.dp, top = 8.dp)
                    .fillMaxWidth()
            ) {
                Text(text = "UID: $uid", style = MaterialTheme.typography.bodySmall)
                Text(text = packageName, style = MaterialTheme.typography.bodySmall)
                Text(text = version, style = MaterialTheme.typography.bodySmall)
                
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onSettingsClick) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onLaunchClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Launch, contentDescription = "Launch")
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun RuleItemPreview() {
    MaterialTheme {
        RuleItem(
            name = "WhatsApp",
            packageName = "com.whatsapp",
            uid = 10101,
            version = "2.23.1.1",
            wifiBlocked = false,
            otherBlocked = true,
            onWifiToggle = {},
            onOtherToggle = {}
        )
    }
}
