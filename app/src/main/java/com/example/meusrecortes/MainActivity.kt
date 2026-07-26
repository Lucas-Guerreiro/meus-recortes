package com.example.meusrecortes

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.meusrecortes.theme.MeusRecortesTheme
import com.example.meusrecortes.video.VideoBufferManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            MeusRecortesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Intercepta botões do SmartControle Bluetooth (Volume +, Volume -, Enter, Espaço, Botão A)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_SPACE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_A) {

            val bufferManager = VideoBufferManager.getInstance(applicationContext)
            if (bufferManager.state.value !is VideoBufferManager.RecordingState.Idle) {
                bufferManager.triggerClip()
                Toast.makeText(this, "Replay Acionado via SmartControle! ⚡", Toast.LENGTH_SHORT).show()
                return true // Consome o evento para não alterar o volume do sistema
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
