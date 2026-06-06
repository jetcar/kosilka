package com.kosilka.feature.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.kosilka.core.ui.design.KosilkaTheme
import com.kosilka.data.device.TransportMode

@Preview(name = "Debug – USB mode", showBackground = true)
@Composable
private fun DebugUsbPreview() {
    KosilkaTheme {
        DebugScreenContent(
            mode = TransportMode.USB,
            serviceEndpoint = "http://10.0.2.2:8080"
        )
    }
}

@Preview(name = "Debug – Service mode", showBackground = true)
@Composable
private fun DebugServicePreview() {
    KosilkaTheme {
        DebugScreenContent(
            mode = TransportMode.SERVICE,
            serviceEndpoint = "http://192.168.1.42:8080"
        )
    }
}
