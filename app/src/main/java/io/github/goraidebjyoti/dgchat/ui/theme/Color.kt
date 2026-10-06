package io.github.goraidebjyoti.dgchat.ui.theme
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.goraidebjyoti.dgchat.data.ThemeColour

private data class Palette(
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
    val onLightContainer: Color,
    val onDarkPrimary: Color,
    val lightSurface: Color,
    val darkSurface: Color,
    val lightVariant: Color,
    val darkVariant: Color)
private fun palette(colour: ThemeColour): Palette=when(colour) {
    ThemeColour.TEAL -> Palette(Color(0xFF1A6460),Color(0xFFC2EAE2),Color(0xFF8DD4C8),Color(0xFF214E47),Color(0xFF123B38),Color(0xFF043A34),Color(0xFFF7F9F6),Color(0xFF101815),Color(0xFFE4EAE5),Color(0xFF35453D))
    ThemeColour.BLUE -> Palette(Color(0xFF205E98),Color(0xFFD5E8FF),Color(0xFFA4CAFF),Color(0xFF234970),Color(0xFF173C61),Color(0xFF08325B),Color(0xFFF7F9FF),Color(0xFF101820),Color(0xFFE2EAF4),Color(0xFF344454))
    ThemeColour.VIOLET -> Palette(Color(0xFF6745A0),Color(0xFFECDDFF),Color(0xFFD4B7FF),Color(0xFF4F376E),Color(0xFF39235C),Color(0xFF362055),Color(0xFFFBF7FF),Color(0xFF1A1422),Color(0xFFEBE4F1),Color(0xFF453A50))
    ThemeColour.ROSE -> Palette(Color(0xFF9A3C61),Color(0xFFFFD9E6),Color(0xFFFFB1CE),Color(0xFF702D49),Color(0xFF60263E),Color(0xFF552035),Color(0xFFFFF7F9),Color(0xFF21151A),Color(0xFFF3E2E9),Color(0xFF513943))
    ThemeColour.AMBER -> Palette(Color(0xFF805600),Color(0xFFFFE3AE),Color(0xFFF4C66B),Color(0xFF58421B),Color(0xFF4B3300),Color(0xFF432E00),Color(0xFFFFFAF1),Color(0xFF1D1810),Color(0xFFF1E7D5),Color(0xFF4A4030))
    ThemeColour.FOREST -> Palette(Color(0xFF356332),Color(0xFFCFECC8),Color(0xFFA4D59A),Color(0xFF2F4F2B),Color(0xFF21431E),Color(0xFF163B16),Color(0xFFF7FAF3),Color(0xFF121A11),Color(0xFFE2EBDD),Color(0xFF394934))
    ThemeColour.SLATE -> Palette(Color(0xFF4A6074),Color(0xFFDAE7F3),Color(0xFFB2CBDD),Color(0xFF344B5C),Color(0xFF233B4D),Color(0xFF1C3445),Color(0xFFF7F9FB),Color(0xFF12191E),Color(0xFFE4EAF0),Color(0xFF3A4650))
}
/** A fixed semantic palette for each choice; light/dark mode remains independent. */
fun colourScheme(colour: ThemeColour,dark: Boolean): ColorScheme {
    val p=palette(colour)
    return if(dark)darkColorScheme(
        primary=p.darkPrimary,onPrimary=p.onDarkPrimary,primaryContainer=p.darkContainer,onPrimaryContainer=p.lightContainer,
        secondary=p.darkPrimary,onSecondary=p.onDarkPrimary,secondaryContainer=p.darkVariant,onSecondaryContainer=p.lightContainer,
        tertiary=p.lightContainer,onTertiary=p.onDarkPrimary,tertiaryContainer=p.darkContainer,onTertiaryContainer=p.lightContainer,
        background=p.darkSurface,onBackground=Color(0xFFE5EAF0),surface=p.darkSurface,onSurface=Color(0xFFE5EAF0),
        surfaceVariant=p.darkVariant,onSurfaceVariant=Color(0xFFD0DAE1),outline=Color(0xFF9BAAB4),outlineVariant=p.darkVariant,
        inverseSurface=p.lightContainer,inverseOnSurface=p.onLightContainer,inversePrimary=p.lightPrimary,surfaceTint=p.darkPrimary,
        surfaceDim=p.darkSurface,surfaceBright=lerp(p.darkSurface,p.darkVariant,0.7f),
        surfaceContainerLowest=lerp(p.darkSurface,Color.Black,0.2f),surfaceContainerLow=lerp(p.darkSurface,p.darkVariant,0.2f),
        surfaceContainer=lerp(p.darkSurface,p.darkVariant,0.35f),surfaceContainerHigh=lerp(p.darkSurface,p.darkVariant,0.5f),surfaceContainerHighest=p.darkVariant)
    else lightColorScheme(
        primary=p.lightPrimary,onPrimary=Color.White,primaryContainer=p.lightContainer,onPrimaryContainer=p.onLightContainer,
        secondary=p.lightPrimary,onSecondary=Color.White,secondaryContainer=p.lightVariant,onSecondaryContainer=p.onLightContainer,
        tertiary=p.lightPrimary,onTertiary=Color.White,tertiaryContainer=p.lightContainer,onTertiaryContainer=p.onLightContainer,
        background=p.lightSurface,onBackground=Color(0xFF182124),surface=p.lightSurface,onSurface=Color(0xFF182124),
        surfaceVariant=p.lightVariant,onSurfaceVariant=Color(0xFF3D454A),outline=Color(0xFF667176),outlineVariant=p.lightVariant,
        inverseSurface=p.onLightContainer,inverseOnSurface=p.lightContainer,inversePrimary=p.darkPrimary,surfaceTint=p.lightPrimary,
        surfaceDim=p.lightVariant,surfaceBright=p.lightSurface,
        surfaceContainerLowest=Color.White,surfaceContainerLow=lerp(p.lightSurface,p.lightContainer,0.1f),
        surfaceContainer=lerp(p.lightSurface,p.lightContainer,0.2f),surfaceContainerHigh=lerp(p.lightSurface,p.lightContainer,0.3f),surfaceContainerHighest=p.lightVariant)
}
val LightColors=colourScheme(ThemeColour.TEAL,false)
val DarkColors=colourScheme(ThemeColour.TEAL,true)
