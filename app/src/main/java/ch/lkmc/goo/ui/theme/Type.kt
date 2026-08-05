package ch.lkmc.goo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Techno signage, not enterprise: heavy weights and WIDE tracking, the way
// every 1999 product wordmark was set. Readouts are monospace, because a
// console that reports numbers should look like it means them.
//
// System families only — the app ships no font assets and fetches nothing
// (PLAN.md §9); FontFamily.SansSerif is condensed-ish and heavy enough on
// every OEM to carry the look on its own.
val MeltoramaTypography = Typography(
    // The wordmark's ceiling, not its fixed size: 9 wide-tracked caps at
    // 36sp need ~250dp, which fits a 360dp screen but not a 320dp one and
    // not a 200% font scale. Wordmark measures and steps this down until
    // it fits — see its `fitted` loop.
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 36.sp,
        letterSpacing = 5.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        letterSpacing = 2.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 1.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp,
        letterSpacing = 1.6.sp,
    ),
    // Panel captions under the tool beads.
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
    // Instrument readouts: quality, punch counts, positions.
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)
