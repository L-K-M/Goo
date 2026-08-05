package ch.lkmc.goo.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// The Meltorama deck: a 1999 idea of the year 2000. Gunmetal console
// panels, machined bevels, chrome rims and neon light — the look KPT Goo
// and its Kai's Power Tools siblings wore, when the future was airbrushed.
// Always dark: there is no light mode of a retro-future control deck.
val MeltVoid = Color(0xFF04050C)
val MeltDeck = Color(0xFF0B0E1C)
val MeltPanel = Color(0xFF161C33)

// Bevel stops. A panel is lit from above: pale top edge, dark bottom edge,
// so every surface reads as milled out of one block of metal.
val MeltPanelHi = Color(0xFF2C3757)
val MeltPanelLo = Color(0xFF080A16)

// Chrome. Three stops are enough for a convincing rim when swept around a
// circle (bright at the top-left and bottom-right, dark at the sides).
val ChromeHi = Color(0xFFDCE7F7)
val ChromeMid = Color(0xFF8494B8)
val ChromeLo = Color(0xFF39456B)

/** The horizon grid on the In screen — the era's favorite backdrop. */
val MeltGrid = Color(0xFF2A3B77)

// Neon accents, one per tool family. Same hue families as the tubes on a
// 1996 box cover: saturated, self-lit, and readable against gunmetal.
val NeonMagenta = Color(0xFFFF3D9E)
val NeonTangerine = Color(0xFFFF9A2E)
val NeonLime = Color(0xFFA6F03F)
val NeonCyan = Color(0xFF35E3F2)
val NeonViolet = Color(0xFFB86BFF)
val NeonAmber = Color(0xFFFFDA3D)

val MeltOnDark = Color(0xFFEAF1FF)
val MeltOnDarkDim = Color(0xFF97A6C9)

/**
 * A polished-metal sweep for rims and bezels: bright where a light source
 * would catch the curve (upper left, lower right), dark at the tangents.
 * Stops must start and end on the same color or the seam shows.
 */
fun chromeSweep(): Brush = Brush.sweepGradient(
    0.00f to ChromeMid,
    0.12f to ChromeHi,
    0.30f to ChromeLo,
    0.50f to ChromeMid,
    0.62f to ChromeHi,
    0.80f to ChromeLo,
    1.00f to ChromeMid,
)

/** Top-lit chrome for flat runs: type, plates, hairlines. */
fun chromePlate(): Brush = Brush.verticalGradient(
    0.00f to ChromeHi,
    0.42f to ChromeMid,
    0.52f to ChromeLo,
    0.62f to ChromeMid,
    1.00f to ChromeHi,
)
