package raul.upt.uwbfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val AppBackground = Color(red = 0.043f, green = 0.059f, blue = 0.078f)

private fun gray(white: Float) = Color(red = white, green = white, blue = white)

/**
 * The single screen of the app. Port of ContentView.swift.
 *
 * @param permissionDenied true when the user refused the Bluetooth (or, on
 *   Android 11 and lower, location) permission needed to scan for Stella.
 */
@Composable
fun ContentScreen(
    ble: BleManager,
    heading: HeadingManager,
    model: RadarModel,
    permissionDenied: Boolean,
) {
    val ui by model.state.collectAsState()
    val connectionState by ble.connectionState.collectAsState()

    // Feed sensor readings into the fusion model (iOS: .onChange(of:)).
    LaunchedEffect(ble, model) {
        ble.distanceM.collect { d -> if (d != null) model.onDistance(d) }
    }
    LaunchedEffect(heading, model) {
        heading.headingDeg.collect { h -> if (h != null) model.onHeading(h) }
    }

    val d = ui.displayedDistanceM
    val distanceText = if (d == null) "-- m" else String.format(Locale.US, "%.2f m", d)

    val statusText = if (permissionDenied) {
        "Bluetooth permission needed to find Stella — check Settings"
    } else {
        when (connectionState) {
            BleManager.ConnectionState.DISCONNECTED -> "searching for Stella…"
            BleManager.ConnectionState.SCANNING -> "scanning…"
            BleManager.ConnectionState.CONNECTING -> "connecting…"
            BleManager.ConnectionState.CONNECTED -> "live"
            BleManager.ConnectionState.BLUETOOTH_OFF -> "Bluetooth is off"
        }
    }

    val confidenceText = if (ui.targetVisible) {
        "direction: ${ui.confidencePct}% confident"
    } else {
        "direction: calibrating — walk around a bit"
    }

    val legendStyle = TextStyle(color = gray(0.6f), fontSize = 12.sp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = distanceText,
                modifier = Modifier.padding(top = 20.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )

            BasicText(
                text = statusText,
                style = TextStyle(color = gray(0.55f), fontSize = 13.sp),
            )

            RadarView(
                state = ui,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendItem(Color(red = 0.29f, green = 0.62f, blue = 1.0f), "you're facing", legendStyle)
                LegendItem(Color.White, "anchor direction", legendStyle)
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DotLegend(Color(red = 0.88f, green = 0.28f, blue = 0.30f), "far", legendStyle)
                DotLegend(Color(red = 0.90f, green = 0.79f, blue = 0.24f), "getting closer", legendStyle)
                DotLegend(Color(red = 0.24f, green = 0.81f, blue = 0.56f), "close", legendStyle)
            }

            BasicText(
                text = confidenceText,
                style = TextStyle(color = gray(0.35f), fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, style: TextStyle) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(14.dp).height(2.dp).background(color))
        BasicText(text = label, style = style)
    }
}

@Composable
private fun DotLegend(color: Color, label: String, style: TextStyle) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
        BasicText(text = label, style = style)
    }
}
