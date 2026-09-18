package uk.co.promptbuilt.notestodos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import uk.co.promptbuilt.notestodos.ui.AppScaffold
import uk.co.promptbuilt.notestodos.ui.theme.NotesTodosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NotesTodosTheme {
                AppScaffold()
            }
        }
    }
}
