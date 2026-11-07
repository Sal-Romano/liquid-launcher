package dev.sal.glass.ui.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.sal.glass.ui.glass.PillShape
import dev.sal.glass.ui.theme.GlassFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val Caption = TextStyle(fontFamily = GlassFonts.ui, fontWeight = FontWeight.Medium, fontSize = 16.sp)
private val pdfLock = Mutex()

/** Longest side of a rendered page or image, px: the screen less a margin. */
private const val MAX_W = 1760
private const val MAX_H = 980

/**
 * Shows what was dropped on the TV: a PDF page by page (Left/Right, or Up/Down), or an image,
 * over a dimmed photo, with a glass caption. Back closes it. Pages are rendered on demand at
 * screen size on a background thread.
 */
@Composable
fun DropViewer(file: File?, serial: Long, visible: Boolean, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    var page by remember(file, serial) { mutableIntStateOf(0) }
    LaunchedEffect(visible, serial) { if (visible) { delay(60); runCatching { focus.requestFocus() } } }
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) {
        val isPdf = file?.name?.lowercase()?.endsWith(".pdf") == true
        val renderer = remember(file, serial) {
            if (file != null && isPdf) runCatching { PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }.getOrNull() else null
        }
        DisposableEffect(renderer) { onDispose { runCatching { renderer?.close() } } }
        val pageCount = renderer?.pageCount ?: 1
        val bitmap by produceState<Bitmap?>(null, file, serial, page) {
            value = withContext(Dispatchers.IO) {
                when {
                    file == null -> null
                    renderer != null -> pdfLock.withLock { renderPage(renderer, page.coerceIn(0, pageCount - 1)) }
                    else -> decodeImage(file)
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.66f))
                .focusRequester(focus)
                .focusable()
                .dismissOnBack(onDismiss)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionRight, Key.DirectionDown, Key.MediaNext, Key.MediaFastForward -> { if (page < pageCount - 1) page++; true }
                        Key.DirectionLeft, Key.DirectionUp, Key.MediaPrevious, Key.MediaRewind -> { if (page > 0) page--; true }
                        else -> false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(bmp.asImageBitmap(), file?.name)
            } else if (file != null && renderer == null && !isPdf) {
                Text("Cannot show ${file.name}", color = Color.White, style = Caption)
            }
            // The caption sits in the corner over the dimmed photo, on its own dark pill (not the
            // GL glass, which paints under this overlay's content and would vanish under a page).
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 30.dp).height(36.dp)
                    .background(Color.Black.copy(alpha = 0.55f), PillShape).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                val caption = buildString {
                    append(file?.name ?: "")
                    if (renderer != null) append("  ·  ${page + 1} / $pageCount")
                }
                Text(caption, color = Color.White, style = Caption, maxLines = 1)
            }
        }
    }
}

private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap? = runCatching {
    renderer.openPage(index).use { p ->
        val scale = minOf(MAX_W.toFloat() / p.width, MAX_H.toFloat() / p.height)
        val w = (p.width * scale).toInt().coerceAtLeast(1)
        val h = (p.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(AColor.WHITE)
        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
    }
}.getOrNull()

private fun decodeImage(file: File): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_W || bounds.outHeight / (sample * 2) >= MAX_H) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scale = minOf(MAX_W.toFloat() / bmp.width, MAX_H.toFloat() / bmp.height, 1f)
    if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
}.getOrNull()
