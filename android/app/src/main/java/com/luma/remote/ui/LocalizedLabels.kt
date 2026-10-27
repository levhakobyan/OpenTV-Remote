package com.luma.remote.ui

import android.content.res.Resources
import com.luma.remote.R
import com.luma.remote.protocol.*
import com.luma.remote.ui.theme.AccentChoice

/** Labels are presentation only; enum names and user-authored text remain unchanged. */
internal fun Resources.label(value:Command):String = getString(when(value) {
    Command.UP -> R.string.label_command_up
    Command.DOWN -> R.string.label_command_down
    Command.LEFT -> R.string.label_command_left
    Command.RIGHT -> R.string.label_command_right
    Command.OK -> R.string.ui_ok
    Command.BACK -> R.string.label_command_back
    Command.HOME -> R.string.label_command_home
    Command.VOLUME_UP -> R.string.label_command_volume_up
    Command.VOLUME_DOWN -> R.string.label_command_volume_down
    Command.MUTE -> R.string.label_command_mute
    Command.PLAY_PAUSE -> R.string.label_command_play_pause
    Command.REWIND -> R.string.label_command_rewind
    Command.FORWARD -> R.string.label_command_forward
    Command.POWER -> R.string.ui_power
    Command.SETTINGS -> R.string.label_command_settings
    Command.INPUT -> R.string.label_command_input
    Command.TV -> R.string.label_command_tv
    Command.APPS -> R.string.label_command_apps
    Command.CHANNEL_UP -> R.string.label_command_channel_up
    Command.CHANNEL_DOWN -> R.string.label_command_channel_down
    Command.GUIDE -> R.string.label_command_guide
    Command.INFO -> R.string.label_command_info
    Command.MENU -> R.string.label_command_menu
    Command.DELETE -> R.string.ui_delete
    Command.ZERO -> R.string.label_command_zero
    Command.ONE -> R.string.label_command_one
    Command.TWO -> R.string.label_command_two
    Command.THREE -> R.string.label_command_three
    Command.FOUR -> R.string.label_command_four
    Command.FIVE -> R.string.label_command_five
    Command.SIX -> R.string.label_command_six
    Command.SEVEN -> R.string.label_command_seven
    Command.EIGHT -> R.string.label_command_eight
    Command.NINE -> R.string.label_command_nine
})

internal fun Resources.label(value:CanvasKind):String = getString(when(value) {
    CanvasKind.KEY -> R.string.ui_button
    CanvasKind.DPAD -> R.string.ui_d_pad
    CanvasKind.TOUCH -> R.string.ui_touchpad
    CanvasKind.APP -> R.string.label_canvaskind_app
    CanvasKind.KEYBOARD -> R.string.ui_keyboard
    CanvasKind.TV -> R.string.ui_tv_button
    CanvasKind.DEVICE -> R.string.label_canvaskind_device
    CanvasKind.VOLUME -> R.string.label_canvaskind_volume
    CanvasKind.CHANNELS -> R.string.label_canvaskind_channels
    CanvasKind.PLAYBACK -> R.string.label_canvaskind_playback
    CanvasKind.NAVIGATION -> R.string.label_canvaskind_navigation
    CanvasKind.NUMBERS -> R.string.ui_numbers
})

internal fun Resources.label(value:TvAction):String = getString(when(value) {
    TvAction.TV -> R.string.label_command_tv
    TvAction.INPUT -> R.string.label_command_input
    TvAction.CHANNEL -> R.string.label_tvaction_channel
    TvAction.APP -> R.string.label_tvaction_app
    TvAction.HIDDEN -> R.string.label_tvaction_hidden
})

internal fun Resources.label(value:HandPreference):String = getString(when(value) {
    HandPreference.RIGHT -> R.string.label_handpreference_right
    HandPreference.LEFT -> R.string.label_handpreference_left
})

internal fun Resources.label(value:ShortcutKind):String = getString(when(value) {
    ShortcutKind.HTTPS -> R.string.label_shortcutkind_https
    ShortcutKind.DEEP_LINK -> R.string.label_shortcutkind_deep_link
    ShortcutKind.PACKAGE -> R.string.label_shortcutkind_package
})

internal fun Resources.label(value:ShortcutIcon):String = getString(when(value) {
    ShortcutIcon.AUTO -> R.string.label_shortcuticon_auto
    ShortcutIcon.VIDEO -> R.string.label_shortcuticon_video
    ShortcutIcon.MUSIC -> R.string.label_shortcuticon_music
    ShortcutIcon.TV -> R.string.ui_tv
    ShortcutIcon.GAME -> R.string.label_shortcuticon_game
    ShortcutIcon.WEB -> R.string.label_shortcuticon_web
})

internal fun Resources.label(value:Platform):String = getString(when(value) {
    Platform.ANDROID_TV -> R.string.label_platform_android_tv
    Platform.LG -> R.string.label_platform_lg
    Platform.SAMSUNG -> R.string.label_platform_samsung
    Platform.ROKU -> R.string.label_platform_roku
    Platform.FIRE_TV -> R.string.label_platform_fire_tv
    Platform.BLUETOOTH -> R.string.label_platform_bluetooth
    Platform.UNKNOWN -> R.string.label_platform_unknown
})

internal fun Resources.label(value:AccentChoice):String = getString(when(value) {
    AccentChoice.LIME -> R.string.label_accentchoice_lime
    AccentChoice.SKY -> R.string.label_accentchoice_sky
    AccentChoice.LAVENDER -> R.string.label_accentchoice_lavender
    AccentChoice.ROSE -> R.string.label_accentchoice_rose
    AccentChoice.AMBER -> R.string.label_accentchoice_amber
    AccentChoice.AQUA -> R.string.label_accentchoice_aqua
})

internal fun Resources.label(value:CanvasTemplate):String = getString(when(value) {
    CanvasTemplate.CLASSIC -> R.string.label_canvastemplate_classic
    CanvasTemplate.CHANNELS -> R.string.label_command_tv
    CanvasTemplate.STREAMING -> R.string.label_canvastemplate_streaming
    CanvasTemplate.TOUCH -> R.string.ui_touchpad
    CanvasTemplate.MINIMAL -> R.string.label_canvastemplate_minimal
    CanvasTemplate.BIG_BUTTONS -> R.string.label_canvastemplate_big_buttons
    CanvasTemplate.FULL -> R.string.label_canvastemplate_full
    CanvasTemplate.NUMBERS_SIDE -> R.string.label_canvastemplate_numbers_side
})

internal fun Resources.description(value:CanvasTemplate):String = getString(when(value) {
    CanvasTemplate.CLASSIC -> R.string.template_description_classic
    CanvasTemplate.CHANNELS -> R.string.template_description_channels
    CanvasTemplate.STREAMING -> R.string.template_description_streaming
    CanvasTemplate.TOUCH -> R.string.template_description_touch
    CanvasTemplate.MINIMAL -> R.string.template_description_minimal
    CanvasTemplate.BIG_BUTTONS -> R.string.template_description_big_buttons
    CanvasTemplate.FULL -> R.string.template_description_full
    CanvasTemplate.NUMBERS_SIDE -> R.string.template_description_numbers_side
})
