package io.github.goraidebjyoti.dgchat.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.github.goraidebjyoti.dgchat.data.ThemeMode
@Composable
fun DgTheme(mode: ThemeMode=ThemeMode.SYSTEM,content: @Composable ()->Unit) {
    val dark=when(mode){ThemeMode.SYSTEM->isSystemInDarkTheme();ThemeMode.LIGHT->false;ThemeMode.DARK->true}
    MaterialTheme(colorScheme=if(dark)DarkColors else LightColors,typography=DgTypography,content=content)
}
