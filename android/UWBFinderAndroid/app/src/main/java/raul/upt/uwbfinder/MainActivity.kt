package raul.upt.uwbfinder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Entry point. Equivalent of UWBFinderApp.swift plus the ContentView lifecycle hooks. */
class MainActivity : ComponentActivity() {

    private lateinit var ble: BleManager
    private lateinit var heading: HeadingManager
    private val model = RadarModel()

    private var permissionDenied by mutableStateOf(false)
    private var permissionRequested = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            permissionDenied = !granted
            if (granted) ble.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark only, so keep light system-bar icons in every system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        ble = BleManager(applicationContext)
        heading = HeadingManager(applicationContext)

        setContent {
            ContentScreen(
                ble = ble,
                heading = heading,
                model = model,
                permissionDenied = permissionDenied,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        heading.start()
        model.start()
        if (BleManager.hasPermissions(this)) {
            permissionDenied = false
            ble.start()
        } else if (!permissionRequested) {
            // Ask once per launch; the status line explains what to do if refused.
            permissionRequested = true
            permissionLauncher.launch(BleManager.requiredPermissions())
        }
    }

    override fun onStop() {
        ble.stop()
        model.stop()
        heading.stop()
        super.onStop()
    }
}
