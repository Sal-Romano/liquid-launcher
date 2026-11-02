package dev.sal.glass.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.sal.glass.R

/** Inter 4.1 static faces (OFL, see INTER-OFL.txt at the project root). */
object GlassFonts {
    /** Display optical size: tighter at the clock's 152 sp. */
    val display = FontFamily(Font(R.font.inter_display_medium, FontWeight.Medium))
    val ui = FontFamily(
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
}
