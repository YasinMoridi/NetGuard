package eu.faircode.netguard.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.faircode.netguard.R

@Composable
fun LegendScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(bottom = 40.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(id = R.drawable.ic_security_color_24dp),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(id = R.string.menu_legend),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            LegendItem(
                icon = R.drawable.ic_security_white_24dp_60,
                text = stringResource(id = R.string.msg_inactive),
                iconBackground = MaterialTheme.colorScheme.primary
            )
            // Note: Some icons use attributes which might need specific handling, 
            // but here we'll use fallback or common drawables.
            
            LegendItem(icon = R.drawable.wifi_on, text = stringResource(id = R.string.title_unmetered_allowed))
            LegendItem(icon = R.drawable.lockdown_on, text = stringResource(id = R.string.title_lockdown_enabled))
            LegendItem(icon = R.drawable.wifi_off, text = stringResource(id = R.string.title_unmetered_blocked))
            LegendItem(icon = R.drawable.wifi_off_disabled, text = stringResource(id = R.string.title_unmetered_disabled))
            LegendItem(icon = R.drawable.other_on, text = stringResource(id = R.string.title_metered_allowed))
            LegendItem(icon = R.drawable.other_off, text = stringResource(id = R.string.title_metered_blocked))
            LegendItem(icon = R.drawable.other_off_disabled, text = stringResource(id = R.string.title_metered_disabled))
            LegendItem(icon = R.drawable.screen_on, text = stringResource(id = R.string.title_interactive_allowed))
            
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.title_roaming_symbol),
                    modifier = Modifier.size(24.dp),
                    fontSize = 14.sp,
                    color = Color.Gray // Placeholder for ?attr/colorOff
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(id = R.string.title_roaming_blocked), style = MaterialTheme.typography.bodyMedium)
            }

            LegendItem(icon = R.drawable.host_allowed, text = stringResource(id = R.string.title_host_allowed))
            LegendItem(icon = R.drawable.host_blocked, text = stringResource(id = R.string.title_host_blocked))

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.title_metered),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun LegendItem(@DrawableRes icon: Int, text: String, iconBackground: Color = Color.Transparent) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(iconBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = icon),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                tint = Color.Unspecified
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
fun LegendScreenPreview() {
    MaterialTheme {
        LegendScreen()
    }
}
