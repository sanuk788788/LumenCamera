package dev.lumen.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private lateinit var engine: CameraEngine
    private lateinit var orientationListener: OrientationEventListener
    private var granted by mutableStateOf(false)

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) engine.resume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = CameraEngine(applicationContext)
        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) engine.deviceOrientation = orientation
            }
        }
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (engine.lenses.isEmpty()) {
                    Centered("No back camera found on this phone")
                } else if (granted) {
                    CameraScreen(engine)
                } else {
                    Centered("Lumen Camera needs camera access", "Allow camera") {
                        permission.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        }
        if (!granted) permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (granted) engine.resume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        super.onPause()
        engine.pause()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    // Volume keys work as the shutter button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) engine.shoot()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
private fun Centered(text: String, button: String? = null, onClick: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White)
        if (button != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
