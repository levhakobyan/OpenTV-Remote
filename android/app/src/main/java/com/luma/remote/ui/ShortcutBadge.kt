package com.luma.remote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.luma.remote.R
import com.luma.remote.protocol.AppShortcut
import com.luma.remote.protocol.ShortcutIcon

@Composable internal fun ShortcutBadge(shortcut:AppShortcut,modifier:Modifier=Modifier) {
    val host=runCatching { java.net.URI(shortcut.url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()
    val brand=if(shortcut.icon==ShortcutIcon.AUTO) when(host) {
        "youtube.com","www.youtube.com","youtu.be" -> R.drawable.ic_youtube
        "netflix.com","www.netflix.com" -> R.drawable.ic_netflix
        else -> null
    } else null
    if(brand!=null)Icon(painterResource(brand),null,modifier,tint=Color.Unspecified)
    else Icon(shortcutGlyph(shortcut.icon),null,modifier)
}
internal fun shortcutGlyph(icon:ShortcutIcon):ImageVector=when(icon) {
    ShortcutIcon.AUTO -> Icons.Outlined.Apps
    ShortcutIcon.VIDEO -> Icons.Outlined.PlayCircleOutline
    ShortcutIcon.MUSIC -> Icons.Outlined.MusicNote
    ShortcutIcon.TV -> Icons.Outlined.Tv
    ShortcutIcon.GAME -> Icons.Outlined.SportsEsports
    ShortcutIcon.WEB -> Icons.Outlined.Language
}
