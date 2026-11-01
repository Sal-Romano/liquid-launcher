package dev.sal.glass

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.sal.glass.data.Apps
import dev.sal.glass.data.Wallpapers
import java.io.File

// Plumbing smoke test only; the real screen replaces this.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }
            var status by remember { mutableStateOf("loading…") }
            LaunchedEffect(Unit) {
                val t0 = SystemClock.elapsedRealtime()
                val uris = Wallpapers.list()
                // Diagnostics: total image rows visible at all, and a direct file read.
                val anyRows = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH), null, null, null,
                )?.use { c ->
                    val paths = mutableSetOf<String>()
                    val rp = c.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                    while (c.moveToNext() && paths.size < 5) paths += (c.getString(rp) ?: "null")
                    "${c.count} rows, paths=$paths"
                } ?: "null cursor"
                val dir = File("/sdcard/Pictures/Wallpapers")
                val direct = "dir.exists=${dir.exists()} canRead=${dir.canRead()} files=${dir.listFiles()?.size}"
                val apps = Apps.launchable(this@MainActivity)
                val t1 = SystemClock.elapsedRealtime()
                bitmap = uris.firstOrNull()?.let { Wallpapers.load(it) }
                val t2 = SystemClock.elapsedRealtime()
                status = "wallpapers=${uris.size} | anyRows=$anyRows | $direct | list+apps=${t1 - t0}ms decode=${t2 - t1}ms " +
                    "bitmap=${bitmap?.width}x${bitmap?.height} apps=${apps.size}"
                Log.i("Glass", status)
            }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                bitmap?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.padding(24.dp)) { Text(status, color = Color.White) }
            }
        }
    }
}
