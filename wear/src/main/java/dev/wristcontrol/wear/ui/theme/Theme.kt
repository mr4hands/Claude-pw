package dev.wristcontrol.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

/**
 * Wear palette. Pure black background is not a style choice — it is free
 * battery on the OLED panels every current Pixel Watch uses.
 */
internal object WristColors {
    val Clay = Color(0xFFD97757)
    val ClayDark = Color(0xFF1A1A18)
    val Bone = Color(0xFFF5F4EF)
    val Dim = Color(0xFF9B9992)
    val Track = Color(0xFF3A3A38)
    val Approve = Color(0xFF2E9E6B)
    val Deny = Color(0xFFC2463B)
    val Surface = Color(0xFF161614)
}

private val WristPalette = Colors(
    primary = WristColors.Clay,
    primaryVariant = WristColors.ClayDark,
    secondary = WristColors.Approve,
    secondaryVariant = WristColors.Track,
    surface = WristColors.Surface,
    error = WristColors.Deny,
    onPrimary = WristColors.ClayDark,
    onSecondary = WristColors.Bone,
    onBackground = WristColors.Bone,
    onSurface = WristColors.Bone,
    onSurfaceVariant = WristColors.Dim,
    onError = WristColors.Bone,
    background = Color.Black,
)

@Composable
fun WristControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WristPalette, content = content)
}
