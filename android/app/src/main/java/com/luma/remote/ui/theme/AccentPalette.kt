package com.luma.remote.ui.theme

import kotlin.math.pow
import kotlin.math.roundToInt

enum class AccentChoice(val title:String,val hex:String) {
    LIME("Lime","D5F69A"), SKY("Sky","9ACBFF"), LAVENDER("Lavender","C9B5FF"),
    ROSE("Rose","FFB2CF"), AMBER("Amber","FFD18A"), AQUA("Aqua","8FE4D5")
}

/** Pure color math: arbitrary seeds become readable accents on a coordinated dark surface. */
data class AccentPalette(val background:Int,val surface:Int,val raised:Int,val accent:Int,val muted:Int,val text:Int,val outline:Int) {
    companion object {
        fun normalize(raw:String?):String?=raw?.removePrefix("#")?.uppercase()?.takeIf { it.matches(Regex("[0-9A-F]{6}")) }
        fun rgb(hex:String):Int=0xff000000.toInt() or hex.toInt(16)
        fun mix(a:Int,b:Int,t:Double):Int {
            fun c(shift:Int)=(((a ushr shift and 255)*(1-t)+(b ushr shift and 255)*t).roundToInt()).coerceIn(0,255)
            return (255 shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        private fun luminance(c:Int):Double {
            fun linear(shift:Int):Double { val x=(c ushr shift and 255)/255.0;return if(x<=.04045)x/12.92 else ((x+.055)/1.055).pow(2.4) }
            return linear(16)*.2126+linear(8)*.7152+linear(0)*.0722
        }
        fun contrast(a:Int,b:Int):Double { val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05) }
        fun from(raw:String?):AccentPalette {
            val seed=rgb(normalize(raw) ?: AccentChoice.LIME.hex)
            val background=mix(rgb("101214"),seed,.025)
            val surface=mix(rgb("1C2022"),seed,.045)
            val raised=mix(rgb("2A3033"),seed,.065)
            var accent=seed
            while(contrast(accent,raised)<4.5)accent=mix(accent,0xffffffff.toInt(),.08)
            return AccentPalette(background,surface,raised,accent,mix(rgb("BDC4C8"),seed,.12),rgb("F4F7F8"),mix(rgb("7B858C"),seed,.14))
        }
    }
}
