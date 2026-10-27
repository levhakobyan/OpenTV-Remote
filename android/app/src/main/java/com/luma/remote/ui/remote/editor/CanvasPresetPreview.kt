package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luma.remote.protocol.*
import com.luma.remote.ui.*

/** Draws the actual candidate geometry without installing live controls or sending commands. */
@Composable internal fun CanvasPresetCard(title:String,description:String,canvas:RemoteCanvas,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    val strings=LocalContext.current.resources

    Surface(onClick=onClick,enabled=enabled,modifier=modifier.semantics { contentDescription=strings.getString(R.string.ui_apply_1_s_preset, title) },shape=RoundedCornerShape(22.dp),color=Panel) {
        Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            CanvasPresetPreview(canvas,Modifier.fillMaxWidth().height(144.dp))
            Text(title,fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
            Text(description,fontSize=12.sp,lineHeight=16.sp,color=Muted)
        }
    }
}
@Composable internal fun CanvasPresetPreview(canvas:RemoteCanvas,modifier:Modifier=Modifier) {
    val ink=Ink;val raised=Raised;val accent=Accent;val muted=Muted
    Canvas(modifier.clearAndSetSemantics {}) {
        val unit=minOf(size.width/6.4f,size.height/(canvas.contentRows+.4f))
        val origin=Offset((size.width-unit*6)/2,(size.height-unit*canvas.contentRows)/2)
        val gap=unit*.09f
        canvas.items.forEach { item ->
            val top=origin+Offset(item.x*unit+gap,item.y*unit+gap)
            val box=Size(item.w*unit-gap*2,item.h*unit-gap*2)
            val center=top+Offset(box.width/2,box.height/2)
            when(item.kind) {
                CanvasKind.DPAD -> {
                    val r=minOf(box.width,box.height)/2
                    drawCircle(raised,r,center);drawCircle(muted.copy(alpha=.6f),r,center,style=Stroke(unit*.04f))
                    drawCircle(accent,r*.34f,center)
                    listOf(Offset(0f,-.7f),Offset(0f,.7f),Offset(-.7f,0f),Offset(.7f,0f)).forEach { drawCircle(muted,unit*.10f,center+it*r) }
                }
                CanvasKind.TOUCH -> {
                    drawRoundRect(raised,top,box,CornerRadius(unit*.35f));drawCircle(accent,unit*.38f,center)
                    for(x in 1..3)for(y in 1..3)drawCircle(muted.copy(alpha=.35f),unit*.025f,top+Offset(box.width*x/4,box.height*y/4))
                }
                CanvasKind.NUMBERS -> {
                    drawRoundRect(ink,top,box,CornerRadius(unit*.25f))
                    for(x in 0..2)for(y in 0..3)drawRoundRect(raised,top+Offset(box.width*x/3+gap,box.height*y/4+gap),Size(box.width/3-gap*2,box.height/4-gap*2),CornerRadius(unit*.12f))
                }
                CanvasKind.VOLUME,CanvasKind.CHANNELS -> {
                    drawRoundRect(raised,top,box,CornerRadius(unit*.4f))
                    if(item.kind==CanvasKind.VOLUME) {
                        for(y in listOf(.24f,.76f)) {
                            val speaker=top+Offset(box.width*.42f,box.height*y)
                            val path=Path().apply {
                                moveTo(speaker.x-unit*.20f,speaker.y-unit*.10f)
                                lineTo(speaker.x-unit*.10f,speaker.y-unit*.10f)
                                lineTo(speaker.x+unit*.05f,speaker.y-unit*.24f)
                                lineTo(speaker.x+unit*.05f,speaker.y+unit*.24f)
                                lineTo(speaker.x-unit*.10f,speaker.y+unit*.10f)
                                lineTo(speaker.x-unit*.20f,speaker.y+unit*.10f);close()
                            }
                            drawPath(path,accent)
                            drawArc(accent,-55f,110f,false,speaker+Offset(-unit*.04f,-unit*.20f),Size(unit*.40f,unit*.40f),style=Stroke(unit*.06f))
                        }
                    } else {
                        for(y in listOf(.24f,.76f))drawLine(muted,top+Offset(box.width*.3f,box.height*y),top+Offset(box.width*.7f,box.height*y),unit*.08f)
                        drawLine(muted,top+Offset(box.width/2,box.height*.17f),top+Offset(box.width/2,box.height*.31f),unit*.08f)
                    }
                }
                CanvasKind.PLAYBACK,CanvasKind.NAVIGATION -> {
                    drawRoundRect(raised,top,box,CornerRadius(unit*.25f))
                    item.kind.keys.forEachIndexed { n,c -> drawCircle(if(c==Command.PLAY_PAUSE)accent else muted,unit*.12f,top+Offset(box.width*(n+.5f)/item.kind.keys.size,box.height/2)) }
                }
                else -> {
                    val selected=item.kind==CanvasKind.APP || item.command in listOf(Command.OK,Command.PLAY_PAUSE)
                    drawRoundRect(if(selected)accent else raised,top,box,CornerRadius(unit*.23f))
                    if(item.kind==CanvasKind.KEYBOARD)for(n in 1..3)drawCircle(muted,unit*.06f,center+Offset((n-2)*unit*.25f,0f))
                    else if(!selected)drawCircle(muted,unit*.065f,center)
                }
            }
        }
    }
}
