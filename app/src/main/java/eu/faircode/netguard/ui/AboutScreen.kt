package eu.faircode.netguard.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.faircode.netguard.R

@Composable
fun AboutScreen(
    versionName: String,
    versionCode: Int,
    onRateClick: () -> Unit = {},
    onEulaClick: () -> Unit = {},
    onPrivacyClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(bottom = 40.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_security_color_24dp),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = versionName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = versionCode.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.app_copyright),
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = onRateClick,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            ) {
                Text(text = stringResource(id = R.string.title_rate))
            }

            TextButton(
                onClick = onEulaClick,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.app_eula),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }

            TextButton(
                onClick = onPrivacyClick,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.app_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen(versionName = "1.0", versionCode = 1)
    }
}
