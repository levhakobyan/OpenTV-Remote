package com.luma.remote.ui

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.ui.theme.AccentPalette
import java.util.Locale

@Composable internal fun CustomColorPicker(initial:String,onDismiss:()->Unit,choose:(String)->Unit) {
    val strings=LocalContext.current.resources

    val initialHsv=remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(AccentPalette.rgb(initial),it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable { mutableStateOf(initial) }
    var fine by rememberSaveable { mutableStateOf(false) }
    fun change(h:Float=hue,s:Float=saturation,v:Float=brightness) {
        hue=h;saturation=s.coerceIn(0f,1f);brightness=v.coerceIn(0f,1f)
        hex=String.format(Locale.ROOT,"%06X",android.graphics.Color.HSVToColor(floatArrayOf(hue,saturation,brightness)) and 0xffffff)
    }
    val valid=AccentPalette.normalize(hex)
    val pureHue=Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,1f,1f)))
    val actual=Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,saturation,brightness)))
    LumaTheme(valid ?: initial) {
        AlertDialog(containerColor=Ink,onDismissRequest=onDismiss,title={Text(strings.getString(R.string.ui_choose_a_color))},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(strings.getString(R.string.ui_drag_to_choose_shade_and_brightness),color=Muted,fontSize=13.sp)
                val update by rememberUpdatedState<(Float,Float)->Unit> { s,v -> change(s=s,v=v) }
                Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {
                    contentDescription=strings.getString(R.string.ui_color_saturation_and_brightness)
                    stateDescription=strings.getString(R.string.ui_1_s_percent_saturation_2_s_percent_brightness, (saturation*100).toInt(), (brightness*100).toInt())
                    customActions=listOf(
                        CustomAccessibilityAction(strings.getString(R.string.ui_more_saturated)) { change(s=saturation+.05f);true },
                        CustomAccessibilityAction(strings.getString(R.string.ui_less_saturated)) { change(s=saturation-.05f);true },
                        CustomAccessibilityAction(strings.getString(R.string.ui_brighter)) { change(v=brightness+.05f);true },
                        CustomAccessibilityAction(strings.getString(R.string.ui_darker)) { change(v=brightness-.05f);true }
                    )
                    onClick(strings.getString(R.string.ui_show_fine_controls)) { fine=true;true }
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down=awaitFirstDown();down.consume()
                        fun pick(point:Offset) { update((point.x/size.width).coerceIn(0f,1f),(1-point.y/size.height).coerceIn(0f,1f)) }
                        pick(down.position)
                        do {
                            val event=awaitPointerEvent()
                            val change=event.changes.firstOrNull { it.id==down.id } ?: break
                            if(change.isConsumed || event.changes.size>1)break
                            pick(change.position);change.consume()
                        } while(change.pressed)
                    }
                }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White,pureHue)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent,Color.Black)))
                    val point=Offset((saturation*size.width).coerceIn(6.dp.toPx(),size.width-6.dp.toPx()),((1-brightness)*size.height).coerceIn(6.dp.toPx(),size.height-6.dp.toPx()))
                    drawCircle(Color.Black,8.dp.toPx(),point,style=Stroke(4.dp.toPx()))
                    drawCircle(Color.White,8.dp.toPx(),point,style=Stroke(2.dp.toPx()))
                }
                Text(strings.getString(R.string.ui_hue),color=Muted,fontSize=13.sp)
                Box(Modifier.fillMaxWidth().height(48.dp),contentAlignment=Alignment.Center) {
                    Box(Modifier.fillMaxWidth().padding(horizontal=10.dp).height(12.dp).background(Brush.horizontalGradient(listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red)),CircleShape))
                    Slider(value=hue,onValueChange={change(h=it)},valueRange=0f..360f,colors=SliderDefaults.colors(activeTrackColor=Color.Transparent,inactiveTrackColor=Color.Transparent),modifier=Modifier.semantics { contentDescription=strings.getString(R.string.ui_color_hue) })
                }
                Surface(color=actual,shape=androidx.compose.foundation.shape.RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().height(36.dp)) {}
                OutlinedTextField(hex,{raw -> if(raw.length<=7) {
                    hex=raw.removePrefix("#").uppercase(Locale.ROOT)
                    AccentPalette.normalize(hex)?.let { value ->
                        val hsv=FloatArray(3);android.graphics.Color.colorToHSV(AccentPalette.rgb(value),hsv)
                        hue=hsv[0];saturation=hsv[1];brightness=hsv[2]
                    }
                }},label={Text(strings.getString(R.string.ui_hex_color))},prefix={Text("#")},singleLine=true,isError=valid==null,supportingText={Text(if(valid==null)strings.getString(R.string.ui_enter_6_hex_digits) else strings.getString(R.string.ui_app_tones_adjust_for_readable_controls))},keyboardOptions=KeyboardOptions(capitalization=KeyboardCapitalization.Characters,keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth())
                TextButton(onClick={fine=!fine}) { Text(if(fine)strings.getString(R.string.ui_hide_fine_controls) else strings.getString(R.string.ui_fine_controls)) }
                if(fine) {
                    Text(strings.getString(R.string.ui_saturation),color=Muted,fontSize=12.sp)
                    Slider(saturation,{change(s=it)},modifier=Modifier.semantics { contentDescription=strings.getString(R.string.ui_color_saturation) })
                    Text(strings.getString(R.string.ui_brightness),color=Muted,fontSize=12.sp)
                    Slider(brightness,{change(v=it)},modifier=Modifier.semantics { contentDescription=strings.getString(R.string.ui_color_brightness) })
                }
            }
        },confirmButton={TextButton(onClick={valid?.let(choose)},enabled=valid!=null) { Text(strings.getString(R.string.ui_choose_color)) }},dismissButton={TextButton(onClick=onDismiss) { Text(strings.getString(R.string.ui_cancel)) }})
    }
}
