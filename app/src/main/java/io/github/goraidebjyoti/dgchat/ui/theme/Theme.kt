package io.github.goraidebjyoti.dgchat.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.github.goraidebjyoti.dgchat.data.ThemeMode
import io.github.goraidebjyoti.dgchat.data.ThemeColour
@Composable
fun DgTheme(mode: ThemeMode=ThemeMode.SYSTEM,colour: ThemeColour=ThemeColour.TEAL,content: @Composable ()->Unit) {
    val dark=when(mode){ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true}
    MaterialTheme(colorScheme=colourScheme(colour,dark),typography=DgTypography,content=content)
}
