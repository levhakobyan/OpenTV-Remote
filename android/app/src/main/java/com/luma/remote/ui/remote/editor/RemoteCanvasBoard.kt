package com.luma.remote.ui.remote.editor

import androidx.compose.ui.platform.LocalContext
import com.luma.remote.R
import com.luma.remote.ui.*
import com.luma.remote.ui.remote.controls.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.luma.remote.ui.remote.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luma.remote.RemoteViewModel
import com.luma.remote.protocol.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Keep the first touch, including touch-slop travel, in the drag's coordinate history. */
private suspend fun PointerInputScope.detectCanvasDrag(start:(Offset)->Unit,move:(Offset)->Unit,end:()->Unit,cancel:()->Unit) {
    awaitEachGesture {
        val down=awaitFirstDown(requireUnconsumed=false)
        var started=false
        try {
            val trigger=awaitTouchSlopOrCancellation(down.id) { change,_ ->
                change.consume();started=true;start(down.position);move(change.position)
            }
            if(trigger!=null) {
                if(drag(trigger.id) { change -> change.consume();move(change.position) }) {
                    // Fast gestures can finish between frames: include the actual release position.
                    currentEvent.changes.firstOrNull { it.changedToUpIgnoreConsumed() }?.let { move(it.position) }
                    end()
                } else cancel()
            }
        } finally { if(started)cancel() }
    }
}

internal fun canvasLabel(item:CanvasItem,apps:List<AppShortcut>,strings:android.content.res.Resources)=when(item.kind) {
    CanvasKind.KEY -> item.command?.let { strings.label(it) } ?: strings.getString(R.string.ui_button)
    CanvasKind.APP -> apps.firstOrNull { it.url==item.app }?.name ?: strings.getString(R.string.ui_app_removed)
    else -> strings.label(item.kind)
}
@Composable private fun CanvasWidget(vm:RemoteViewModel,item:CanvasItem,state:RemoteState,apps:List<AppShortcut>,keyboard:()->Unit,numbers:()->Unit,tvSetup:()->Unit) {
    val strings=LocalContext.current.resources

    val caps=state.capabilities
    if(!item.supported(caps,apps)) {
        Box(Modifier.fillMaxSize().background(Panel,RoundedCornerShape(16.dp)).semantics { contentDescription=strings.getString(R.string.ui_1_s_unavailable_on_this_tv, canvasLabel(item,apps,strings)) },contentAlignment=Alignment.Center) { Text(strings.getString(R.string.ui_unavailable),fontSize=11.sp,color=Muted) };return
    }
    when(item.kind) {
        CanvasKind.KEY -> RemoteCommand(vm,item.command!!,Modifier.fillMaxSize())
        CanvasKind.DPAD -> BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Dpad(vm,caps,minOf(maxWidth,maxHeight)) }
        CanvasKind.TOUCH -> { val device by vm.selected.collectAsStateWithLifecycle();GesturePad(vm,device?.id ?: "",caps,Modifier.fillMaxSize()) }
        CanvasKind.APP -> { val app=apps.first { it.url==item.app };FilledTonalButton(onClick={vm.launchApp(app)},modifier=Modifier.fillMaxSize().semantics { contentDescription=app.name },contentPadding=PaddingValues(4.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Panel)) { ShortcutBadge(app,Modifier.size(24.dp));Spacer(Modifier.width(6.dp));Text(app.name,maxLines=1,overflow=TextOverflow.Ellipsis,fontSize=12.sp) } }
        CanvasKind.KEYBOARD -> CanvasAction(strings.getString(R.string.ui_keyboard),Icons.Outlined.Keyboard,keyboard)
        CanvasKind.TV -> { val layout by vm.layout.collectAsStateWithLifecycle();CanvasAction(if(layout.tvConfigured && (layout.tvAction.command in caps.commands || layout.tvAction==TvAction.APP && caps.apps && apps.any { it.url==layout.tvApp }))strings.label(layout.tvAction) else strings.getString(R.string.ui_tv_setup),Icons.Outlined.Tv,tvSetup) }
        CanvasKind.DEVICE -> {
            val device by vm.selected.collectAsStateWithLifecycle()
            val name=device?.name ?: strings.getString(R.string.ui_switch_tv)
            CanvasAction(if(name.length>20)name.take(19)+"…" else name,Icons.Outlined.SwapHoriz,{vm.page.value=1},strings.getString(R.string.ui_switch_tv_1_s, name))
        }
        CanvasKind.VOLUME,CanvasKind.CHANNELS -> RemoteRocker(vm,item.kind)
        CanvasKind.PLAYBACK,CanvasKind.NAVIGATION -> RemoteButtonGroup(vm,item.kind)
        CanvasKind.NUMBERS -> Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            AndroidTvProtocol.digits.drop(1).chunked(3).forEach { row -> Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) { row.forEach { RemoteCommand(vm,it,Modifier.weight(1f).fillMaxHeight()) } } }
            Row(Modifier.fillMaxWidth().weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                if(Command.DELETE in caps.commands)RemoteCommand(vm,Command.DELETE,Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                RemoteCommand(vm,Command.ZERO,Modifier.weight(1f).fillMaxHeight());if(Command.OK in caps.commands)RemoteCommand(vm,Command.OK,Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
            }
        }
    }
}
@Composable private fun CanvasAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,action:()->Unit,description:String=label) {
    FilledTonalButton(onClick=action,modifier=Modifier.fillMaxSize().semantics { contentDescription=description },contentPadding=PaddingValues(4.dp),shape=RoundedCornerShape(16.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=Panel)) { Icon(icon,null,Modifier.size(24.dp));Spacer(Modifier.width(6.dp));Text(label,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis) }
}

/** Fixed design coordinates survive rotation. Empty grid space can scroll; controls drag directly. */
@Composable internal fun RemoteCanvasBoard(vm:RemoteViewModel,canvas:RemoteCanvas,state:RemoteState,apps:List<AppShortcut>,modifier:Modifier=Modifier,editing:Boolean=false,resizeMode:Boolean=false,selected:String?=null,onSelect:(String)->Unit={},onChange:(RemoteCanvas)->Unit={},onInvalid:()->Unit={},keyboard:()->Unit={},numbers:()->Unit={},tvSetup:()->Unit={},edit:(()->Unit)?=null) {
    val strings=LocalContext.current.resources

    if(canvas.items.isEmpty() && !editing) {
        Column(modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp,Alignment.CenterVertically)) {
            Text(strings.getString(R.string.ui_your_remote_is_empty),color=Muted)
            if(edit!=null)FilledTonalButton(onClick=edit,modifier=Modifier.heightIn(min=48.dp)) { Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(8.dp));Text(strings.getString(R.string.ui_add_controls)) }
        };return
    }
    val drawInk=Ink;val drawMuted=Muted
    val latest by rememberUpdatedState(canvas)
    val latestSelection by rememberUpdatedState(selected)
    val latestResizeMode by rememberUpdatedState(resizeMode)
    val density=LocalDensity.current
    val haptic=LocalHapticFeedback.current
    val scroll=rememberScrollState()
    var dragging by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf<CanvasItem?>(null) }
    var delta by remember { mutableStateOf(Offset.Zero) }
    var resize by remember { mutableStateOf(false) }
    var edge by remember { mutableIntStateOf(0) }
    var viewportY by remember { mutableFloatStateOf(0f) }
    var viewportH by remember { mutableIntStateOf(0) }
    LaunchedEffect(dragging,edge) { while(dragging!=null && edge!=0) {
        val before=scroll.value;scroll.scrollTo((before+with(density){12.dp.roundToPx()}*edge).coerceIn(0,scroll.maxValue))
        val moved=scroll.value-before;if(moved==0) { delay(32);continue }
        delta+=Offset(0f,moved.toFloat());delay(16)
    } }
    BoxWithConstraints(modifier.fillMaxWidth().onGloballyPositioned { viewportY=it.positionInWindow().y;viewportH=it.size.height }) {
        val availableHeight=maxHeight
        val width=maxOf(maxWidth,308.dp)
        val cell=(width-20.dp)/6
        val occupiedRows=canvas.contentRows
        val row=if(!editing)CanvasFit.rowHeight(cell.value.toDouble(),maxHeight.value.toDouble(),occupiedRows,4.0).dp else maxOf(48.dp,cell)
        val stepX=with(density){(cell+4.dp).toPx()};val stepY=with(density){(row+4.dp).toPx()}
        fun candidate():RemoteCanvas? {
            val i=original ?: return null
            return if(resize)latest.resizePushingDown(i.id,i.w+(delta.x/stepX).roundToInt(),i.h+(delta.y/stepY).roundToInt())
            else latest.place(i.id,i.x+(delta.x/stepX).roundToInt(),i.y+(delta.y/stepY).roundToInt())
        }
        val proposal=if(editing && dragging!=null)candidate() else null
        val liveBottom=original?.let { it.y+it.h+(delta.y/stepY).roundToInt() } ?: occupiedRows
        val previewRows=if(editing && dragging!=null)(maxOf(occupiedRows,liveBottom,proposal?.contentRows ?: 0)+2).coerceIn(1,24) else occupiedRows
        val boardHeight=maxOf(row*previewRows+4.dp*(previewRows-1),if(editing)maxHeight else 0.dp)
        val picked=canvas.items.firstOrNull { it.id==selected }
        LaunchedEffect(selected,picked?.x,picked?.y,picked?.w,picked?.h,viewportH) {
            if(!editing || dragging!=null || picked==null || viewportH<=0)return@LaunchedEffect
            withFrameNanos { }
            val top=(picked.y*stepY).roundToInt()
            val bottom=top+with(density) { (row*picked.h+4.dp*(picked.h-1)).roundToPx() }
            val target=if(top<scroll.value)top else if(bottom>scroll.value+viewportH)bottom-viewportH else scroll.value
            scroll.animateScrollTo(target.coerceIn(0,scroll.maxValue))
        }
        fun finish() { candidate()?.let(onChange) ?: onInvalid();dragging=null;original=null;delta=Offset.Zero;edge=0;resize=false }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
        Box(Modifier.width(width).verticalScroll(scroll).heightIn(min=if(editing)0.dp else availableHeight),contentAlignment=Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height(boardHeight)) {
                if(editing)Canvas(Modifier.fillMaxSize().pointerInput(selected,stepX,stepY) {
                    detectTapGestures { position ->
                        val picked=latest.items.firstOrNull { it.id==selected }
                        if(picked!=null) {
                            val next=latest.place(picked.id,(position.x/stepX).toInt(),(position.y/stepY).toInt())
                            if(next!=null)onChange(next) else onInvalid()
                        }
                    }
                }) {
                    for(y in 0 until (if(canvas.items.isEmpty() && dragging==null)0 else previewRows))for(x in 0 until 6)drawRoundRect(drawMuted.copy(alpha=.10f),topLeft=Offset(x*stepX,y*stepY),size=androidx.compose.ui.geometry.Size(with(density){cell.toPx()},with(density){row.toPx()}),cornerRadius=androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),style=Stroke(1.dp.toPx()))
                }
                if(editing && canvas.items.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text(strings.getString(R.string.ui_add_a_control_to_start),color=Muted,fontSize=14.sp) }
                if(editing && dragging!=null) {
                    val base=original
                    if(base!=null) {
                        val target=if(resize)base.copy(w=base.w+(delta.x/stepX).roundToInt(),h=base.h+(delta.y/stepY).roundToInt())
                            else base.copy(x=base.x+(delta.x/stepX).roundToInt(),y=base.y+(delta.y/stepY).roundToInt())
                        val allowed=proposal!=null
                        val color=if(allowed)Accent else MaterialTheme.colorScheme.error
                        Canvas(Modifier.matchParentSize().zIndex(3f).clearAndSetSemantics {}) {
                            val origin=Offset(target.x*stepX,target.y*stepY)
                            val span=Size((target.w.coerceIn(base.kind.minW,6)*stepX-4.dp.toPx()),(target.h.coerceIn(base.kind.minH,24)*stepY-4.dp.toPx()))
                            drawRoundRect(color.copy(alpha=.14f),origin,span,CornerRadius(12.dp.toPx()))
                            drawRoundRect(color,origin,span,CornerRadius(12.dp.toPx()),style=Stroke(2.dp.toPx(),pathEffect=if(allowed)null else PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(),5.dp.toPx()))))
                            val badge=origin+Offset(span.width-18.dp.toPx(),18.dp.toPx())
                            drawCircle(drawInk,11.dp.toPx(),badge)
                            if(allowed) {
                                drawLine(color,badge+Offset(-6.dp.toPx(),0f),badge+Offset(-1.dp.toPx(),5.dp.toPx()),2.dp.toPx())
                                drawLine(color,badge+Offset(-1.dp.toPx(),5.dp.toPx()),badge+Offset(7.dp.toPx(),-5.dp.toPx()),2.dp.toPx())
                            } else {
                                drawLine(color,badge+Offset(-5.dp.toPx(),-5.dp.toPx()),badge+Offset(5.dp.toPx(),5.dp.toPx()),2.dp.toPx())
                                drawLine(color,badge+Offset(-5.dp.toPx(),5.dp.toPx()),badge+Offset(5.dp.toPx(),-5.dp.toPx()),2.dp.toPx())
                            }
                        }
                    }
                }
                canvas.items.forEach { item -> key(item.id) {
                    val currentItem by rememberUpdatedState(item)
                    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    var resizeCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    val active=dragging==item.id
                    val shown=if(resize && !active)proposal?.items?.firstOrNull { it.id==item.id } ?: item else item
                    val shifted=shown.y!=item.y
                    val w=if(active && resize)(item.w+(delta.x/stepX).roundToInt()).coerceIn(item.kind.minW,6) else item.w
                    val h=if(active && resize)(item.h+(delta.y/stepY).roundToInt()).coerceIn(item.kind.minH,24-item.y) else item.h
                    val invalid=active && proposal==null
                    Box(Modifier.offset { IntOffset((shown.x*stepX).roundToInt(),(shown.y*stepY).roundToInt()) }.drawWithContent {
                        // Move only the drawing; the captured touch surface stays fixed throughout a drag.
                        translate(if(active && !resize)delta.x else 0f,if(active && !resize)delta.y else 0f) { this@drawWithContent.drawContent() }
                    }.width(cell*w+4.dp*(w-1)).height(row*h+4.dp*(h-1)).zIndex(if(active)2f else if(item.id==selected)1f else 0f).onGloballyPositioned { coordinates=it }) {
                        if(editing)Box(Modifier.fillMaxSize().focusProperties { canFocus=false }.clearAndSetSemantics {}) { CanvasWidget(vm,item,state,apps,keyboard,numbers,tvSetup) }
                        else CanvasWidget(vm,item,state,apps,keyboard,numbers,tvSetup)
                        if(editing)Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp)).background(if(active)Ink.copy(alpha=.18f) else Color.Transparent).border(if(item.id==selected || active || shifted)2.dp else 1.dp,if(invalid)MaterialTheme.colorScheme.error else if(item.id==selected || active || shifted)Accent else Muted.copy(alpha=.45f),RoundedCornerShape(16.dp)).semantics { role=Role.Button;contentDescription=strings.getString(R.string.ui_edit_1_s, canvasLabel(item,apps,strings))
                            customActions=listOf(Triple(-1,0,strings.getString(R.string.ui_left)),Triple(0,-1,strings.getString(R.string.ui_up)),Triple(0,1,strings.getString(R.string.ui_down)),Triple(1,0,strings.getString(R.string.ui_right))).map { (dx,dy,label) ->
                                CustomAccessibilityAction(strings.getString(R.string.ui_move_1_s, label)) {
                                    val next=latest.place(item.id,currentItem.x+dx,currentItem.y+dy)
                                    if(next!=null)onChange(next) else onInvalid();true
                                }
                            };onClick(strings.getString(R.string.ui_select_control)) { onSelect(item.id);true } }.onKeyEvent { if(it.type==KeyEventType.KeyUp && it.key in setOf(Key.Enter,Key.DirectionCenter,Key.Spacebar)) { onSelect(item.id);true } else false }.focusable().pointerInput(item.id) { detectTapGestures(onTap={onSelect(item.id)}) }.pointerInput(item.id,item.x,item.y,item.w,item.h,stepX,stepY) {
                            var origin=Offset.Zero
                            var initialScroll=0
                            var originWindowY=0f
                            detectCanvasDrag(start={ position ->
                                origin=position;initialScroll=scroll.value;originWindowY=(coordinates?.localToWindow(position) ?: position).y
                                original=currentItem;dragging=item.id;delta=Offset.Zero
                                resize=latestResizeMode && item.id==latestSelection
                                onSelect(item.id)
                                if(vm.haptics)haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },move={ position ->
                                // Horizontal travel uses the fixed surface; window Y compensates scrolling exactly once.
                                val pointer=coordinates?.localToWindow(position) ?: position
                                delta=Offset(position.x-origin.x,pointer.y-originWindowY+(scroll.value-initialScroll).toFloat())
                                edge=if(pointer.y<viewportY+48.dp.toPx())-1 else if(pointer.y>viewportY+viewportH-48.dp.toPx())1 else 0
                            },end={finish()},cancel={dragging=null;original=null;delta=Offset.Zero;resize=false;edge=0})
                        })
                        if(editing && item.id==selected && (resizeMode || item.w>1 && item.h>1))Box(Modifier.align(Alignment.TopStart).offset { IntOffset((item.w*stepX-with(density){52.dp.toPx()}).roundToInt(),(item.h*stepY-with(density){52.dp.toPx()}).roundToInt()) }.drawWithContent {
                            translate(if(active && resize)delta.x else 0f,if(active && resize)delta.y else 0f) { this@drawWithContent.drawContent() }
                        }.size(48.dp).onGloballyPositioned { resizeCoordinates=it }.background(Accent.copy(alpha=.18f),RoundedCornerShape(topStart=16.dp,bottomEnd=16.dp)).semantics { contentDescription=strings.getString(R.string.ui_resize_1_s, canvasLabel(item,apps,strings)) }.pointerInput(item.id,item.x,item.y,item.w,item.h,stepX,stepY) {
                            var origin=Offset.Zero
                            var initialScroll=0
                            var originWindowY=0f
                            detectCanvasDrag(start={ position ->
                                origin=position;initialScroll=scroll.value;originWindowY=(resizeCoordinates?.localToWindow(position) ?: position).y
                                original=currentItem;dragging=item.id;delta=Offset.Zero;resize=true
                            },move={ position ->
                                val pointer=resizeCoordinates?.localToWindow(position) ?: position
                                delta=Offset(position.x-origin.x,pointer.y-originWindowY+(scroll.value-initialScroll).toFloat())
                                edge=if(pointer.y<viewportY+48.dp.toPx())-1 else if(pointer.y>viewportY+viewportH-48.dp.toPx())1 else 0
                            },end={finish()},cancel={dragging=null;original=null;delta=Offset.Zero;resize=false;edge=0})
                        },contentAlignment=Alignment.Center) { Icon(Icons.Outlined.OpenInFull,null,Modifier.size(18.dp),tint=Accent) }
                    }
                } }
            }
        }
    }
}
}
