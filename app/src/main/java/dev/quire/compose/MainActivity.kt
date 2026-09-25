package dev.quire.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.quire.compose.ui.QuireApp

/**
 * The only Activity.
 *
 * It owns three things: edge-to-edge drawing (Compose paints the whole window,
 * including behind the system bars), the foreground signal the debounced writer
 * needs, and nothing else — the session belongs to [QuireViewModel], which
 * outlives a configuration change.
 */
class MainActivity : ComponentActivity() {

    private val vm: QuireViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { QuireApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        // In front: the writer's tick starts, so edits reach the database about a
        // second after they stop.
        vm.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // Away: write now. Android gives no reliable "about to be killed", so the
        // honest policy is to be already written by the time anyone asks.
        vm.setForeground(false)
    }
}
