package dev.sal.glass

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.sal.glass.config.ConfigStore
import dev.sal.glass.drop.DropServer
import dev.sal.glass.ui.HomeScreen
import dev.sal.glass.wallpaper.WallpaperEngine
import java.io.File

class MainActivity : ComponentActivity() {
    private val engine: WallpaperEngine by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = WallpaperEngine(applicationContext) as T
        }
    }
    private val store by lazy { ConfigStore(this) }
    /** Files dropped on the TV from a laptop or phone land here and open on screen. */
    private val drop by lazy { DropServer(File(filesDir, "drop")) }
    /** Bumped on every Home press (onNewIntent) so the screen wakes and rescans. */
    private var wake by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        drop.start()
        // A launcher never finishes on Back, whatever state it is in; the screen decides what Back
        // means (hide the dock, close an overlay). On the TV Fire OS never delivers Back out of a
        // home app anyway; this covers the emulator and any future non-home use.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        setContent { HomeScreen(engine, store, wake) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        wake = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        wake = System.currentTimeMillis()
    }

    override fun onDestroy() {
        drop.stop()
        super.onDestroy()
    }
}
