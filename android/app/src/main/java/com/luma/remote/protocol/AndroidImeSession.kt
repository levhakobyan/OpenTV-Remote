package com.luma.remote.protocol

/** Ephemeral UTF-16 editor state. Never persisted, and deliberately redacted in diagnostics. */
data class TvImeValue(val text:String,val start:Int=text.length,val end:Int=start) {
    fun valid()=text.length<=4096 && ImeRanges.validText(text) && start in 0..text.length && end in 0..text.length && ImeRanges.boundary(text,start) && ImeRanges.boundary(text,end)
    override fun toString()="TvImeValue(redacted)"
}
enum class TvImeMode { UNAVAILABLE, LIVE, MANUAL, CONFLICT, PRIVATE }
data class TvImeState(val session:Long=0,val revision:Long=0,val active:Boolean=false,val value:TvImeValue?=null,val mode:TvImeMode=TvImeMode.UNAVAILABLE,val pending:Boolean=false,val clientRevision:Long=0,val externalVersion:Long=0) {
    override fun toString()="TvImeState(session=$session, active=$active, mode=$mode, pending=$pending)"
}
internal data class ImeReplacement(val start:Int,val end:Int,val text:String) {
    override fun toString()="ImeReplacement($start,$end,redacted)"
}
internal object ImeRanges {
    fun boundary(text:String,index:Int)=index in 0..text.length && !(index>0 && index<text.length && text[index-1].isHighSurrogate() && text[index].isLowSurrogate())
    fun validText(text:String):Boolean {
        var i=0
        while(i<text.length) {
            val c=text[i++]
            if(c.isLowSurrogate())return false
            if(c.isHighSurrogate() && (i>=text.length || !text[i++].isLowSurrogate()))return false
        }
        return true
    }
    fun difference(before:String,after:String):ImeReplacement? {
        require(validText(before) && validText(after))
        if(before==after)return null
        var prefix=0
        while(prefix<minOf(before.length,after.length) && before[prefix]==after[prefix])prefix++
        while(!boundary(before,prefix) || !boundary(after,prefix))prefix--
        var oldEnd=before.length;var newEnd=after.length
        while(oldEnd>prefix && newEnd>prefix && before[oldEnd-1]==after[newEnd-1]) { oldEnd--;newEnd-- }
        while(!boundary(before,oldEnd) || !boundary(after,newEnd)) { oldEnd++;newEnd++ }
        return ImeReplacement(prefix,oldEnd,after.substring(prefix,newEnd))
    }
    /** Anchor to the user's resulting caret so repeated text cannot move a deletion elsewhere. */
    fun difference(before:TvImeValue,after:TvImeValue):ImeReplacement? {
        if(before.text==after.text)return null
        if(after.start==after.end) {
            val caret=after.end
            val oldEnd=before.text.length-(after.text.length-caret)
            if(boundary(before.text,oldEnd) && boundary(after.text,caret) && before.text.substring(oldEnd)==after.text.substring(caret)) {
                var common=0
                while(common<minOf(before.text.length,after.text.length) && before.text[common]==after.text[common])common++
                var start=minOf(common,caret,oldEnd)
                while(!boundary(before.text,start) || !boundary(after.text,start))start--
                return ImeReplacement(start,oldEnd,after.text.substring(start,caret))
            }
        }
        return difference(before.text,after.text)
    }
}
internal data class ImeEdit(val session:Long,val id:Long,val imeCounter:Int,val fieldCounter:Int,val replacement:ImeReplacement,val expected:TvImeValue,val localRevision:Long) {
    override fun toString()="ImeEdit(session=$session,id=$id,redacted)"
    fun bytes():ByteArray {
        val obj=Wire.message(Wire.int(1,replacement.start),Wire.int(2,replacement.end),Wire.string(3,replacement.text))
        return Wire.bytes(21,Wire.message(Wire.int(1,imeCounter),Wire.int(2,fieldCounter),Wire.bytes(3,Wire.message(Wire.int(1,1),Wire.bytes(2,obj)))))
    }
}

/** One field/connection at a time, with one in-flight replacement and no speculative replay. */
internal class AndroidImeSession {
    companion object { private val epochs=java.util.concurrent.atomic.AtomicLong() }
    private var generation=epochs.incrementAndGet()
    private var revision=0L
    private var localRevision=0L
    private var externalVersion=0L
    private var sequence=0L
    private var imeCounter=0
    private var fieldCounter=0
    private var server:TvImeValue?=null
    private var desired:TvImeValue?=null
    private var composing=false
    private var pending:ImeEdit?=null
    private var mode=TvImeMode.UNAVAILABLE
    private var active=false
    private var packageName:String?=null
    private var editorIdentity:Pair<Int,Int>?=null
    private var fieldLabel:String?=null
    private var counterObserved=false
    private var dismissed=false
    private data class Context(val app:String?,val editor:Pair<Int,Int>?)
    private val retiredContexts=linkedSetOf<Context>()
    private val retiredImeCounters=linkedSetOf<Int>()
    private var liveDisabled=false
    private var ambiguousShow=false
    private var privateContext=false
    private var safeSelection:Pair<Int,Int>?=null
    val snapshot:TvImeState get()=TvImeState(generation,revision,active,desired,if(privateContext)TvImeMode.PRIVATE else if(mode==TvImeMode.LIVE && (!counterObserved || liveDisabled))TvImeMode.MANUAL else mode,pending!=null,localRevision,externalVersion)
    fun reset() { generation=epochs.incrementAndGet();revision++;localRevision=0;server=null;desired=null;pending=null;composing=false;active=false;mode=TvImeMode.UNAVAILABLE;counterObserved=false;imeCounter=0;fieldCounter=0;packageName=null;editorIdentity=null;fieldLabel=null;dismissed=false;retiredContexts.clear();retiredImeCounters.clear();liveDisabled=false;ambiguousShow=false;privateContext=false;safeSelection=null }
    fun detach() { generation=epochs.incrementAndGet();revision++;localRevision=0;pending=null;desired=null;server=null;composing=false;active=false;mode=TvImeMode.UNAVAILABLE;fieldLabel=null;safeSelection=null }
    fun dismiss() {detach();dismissed=true}
    fun fallback() { pending=null;composing=false;mode=if(privateContext)TvImeMode.PRIVATE else TvImeMode.MANUAL;revision++ }
    fun manualInsertion(value:String):ByteArray {
        require(value.isNotEmpty() && value.length<=1000 && ImeRanges.validText(value))
        val selection=safeSelection ?: return AndroidTvProtocol.text(value,imeCounter,fieldCounter)
        val objectBytes=Wire.message(Wire.int(1,minOf(selection.first,selection.second)),Wire.int(2,maxOf(selection.first,selection.second)),Wire.string(3,value))
        return Wire.bytes(21,Wire.message(Wire.int(1,imeCounter),Wire.int(2,fieldCounter),Wire.bytes(3,Wire.message(Wire.int(1,1),Wire.bytes(2,objectBytes)))))
    }
    fun expire(session:Long,id:Long) { if(pending?.let { it.session==session && it.id==id }==true)fallback() }
    fun local(session:Long,value:TvImeValue,isComposing:Boolean,editRevision:Long=localRevision+1,baseVersion:Long=externalVersion):Boolean {
        if(liveDisabled || editRevision<=localRevision || session!=generation || !active || mode!=TvImeMode.LIVE || !value.valid())return false
        desired=value;composing=isComposing;localRevision=editRevision;revision++
        if(baseVersion!=externalVersion) {pending=null;composing=false;mode=TvImeMode.CONFLICT;return false}
        return true
    }
    fun canEnter(session:Long,expectedRevision:Long):Boolean = !liveDisabled && session==generation && active && !composing && pending==null && localRevision==expectedRevision && desired?.text==server?.text && mode==TvImeMode.LIVE
    fun isPending(session:Long,id:Long)=pending?.let {it.session==session && it.id==id}==true
    fun nextEdit():ImeEdit? {
        if(liveDisabled || !active || mode!=TvImeMode.LIVE || pending!=null || !counterObserved)return null
        val base=server ?: return null;val target=desired ?: return null
        val change=ImeRanges.difference(base,target) ?: return null
        return ImeEdit(generation,++sequence,imeCounter,fieldCounter,change,target,localRevision).also { pending=it;revision++ }
    }
    /** A zero batch is ambiguous on real TVs; it neither closes a field nor regresses known counters. */
    fun receive(fields:List<Wire.Field>):Boolean {
        var show=false
        fields.firstOrNull { it.number==21 }?.bytes?.let batchBlock@{ bytes ->
            val batch=Wire.parse(bytes)
            val incomingIme=batch.num(1);val incomingField=batch.num(2)
            if(incomingIme>=0 && incomingField>=0) {
                if(incomingIme!=0 && incomingIme in retiredImeCounters)return@batchBlock
                if(incomingIme!=0 || !counterObserved) {
                    if(counterObserved && incomingIme!=imeCounter) {
                        if(retiredImeCounters.size>=64)liveDisabled=true else retiredImeCounters+=imeCounter
                        detach();dismissed=false;privateContext=false;ambiguousShow=false
                    }
                    imeCounter=incomingIme
                }
                if(incomingField!=0 || !counterObserved)fieldCounter=maxOf(fieldCounter,incomingField)
                counterObserved=true
            }
        }
        fields.firstOrNull { it.number==20 }?.bytes?.let appBlock@{ bytes ->
            val msg=Wire.parse(bytes);val app=msg.nested(1)
            val incomingPackage=app.firstOrNull { it.number==12 }?.bytes?.toString(Charsets.UTF_8)
            val incomingIdentity=if(app.any { it.number==1 } || app.any { it.number==13 })app.num(1) to app.num(13) else null
            val changed=(incomingPackage!=null && packageName!=null && incomingPackage!=packageName) || (incomingIdentity!=null && editorIdentity!=null && incomingIdentity!=editorIdentity)
            if(changed) {
                val next=Context(incomingPackage ?: packageName,incomingIdentity ?: editorIdentity)
                if(next in retiredContexts) {ambiguousShow=true;return@appBlock}
                ambiguousShow=false
                if(retiredContexts.size>=64)liveDisabled=true else retiredContexts+=Context(packageName,editorIdentity)
                detach();dismissed=false;privateContext=false
            }
            if(incomingPackage!=null)packageName=incomingPackage
            if(incomingIdentity!=null)editorIdentity=incomingIdentity
            val status=msg.firstOrNull { it.number==2 }?.bytes
            if(status!=null)receiveStatus(Wire.parse(status),false)
            else if(changed || app.isNotEmpty())detach()
        }
        fields.firstOrNull { it.number==22 }?.bytes?.let { bytes ->
            val status=Wire.parse(bytes).firstOrNull { it.number==2 }?.bytes
            if(status==null)detach()
            else { show=receiveStatus(Wire.parse(status),true) }
        }
        return show
    }
    private fun opaque(count:Int,start:Int,end:Int,show:Boolean):Boolean {
        if(!privateContext || show) {generation=epochs.incrementAndGet();localRevision=0;externalVersion++}
        privateContext=true;active=true;pending=null;composing=false;server=null;desired=null;mode=TvImeMode.PRIVATE
        if(count>=0)fieldCounter=count
        safeSelection=if(start in 0..4096 && end in 0..4096)start to end else null
        revision++
        return show
    }
    private fun receiveStatus(status:List<Wire.Field>,show:Boolean):Boolean {
        if(show && ambiguousShow) {
            ambiguousShow=false;generation=epochs.incrementAndGet();localRevision=0;pending=null;composing=false;mode=TvImeMode.CONFLICT;revision++
            return false
        }
        if(dismissed && !show)return false
        if(show)dismissed=false
        val count=status.num(1)
        if(!show && active && count>=0 && count<fieldCounter)return false
        if(show && active && count>=0 && count<fieldCounter) {
            // A late show request is not proof that the TV switched to a lower-revision field.
            generation=epochs.incrementAndGet();localRevision=0;pending=null;composing=false;mode=TvImeMode.CONFLICT;revision++
            return true
        }
        val start=status.num(3);val end=status.num(4)
        val label=status.firstOrNull { it.number==6 }?.bytes?.toString(Charsets.UTF_8).orEmpty().lowercase()
        val privateLabel=listOf("password","passcode","парол","գաղտնաբառ").any { label.contains(it) } || Regex("\\bpin\\b").containsMatchIn(label)
        // A protected context stays opaque even when later echoes look unmasked or omit labels.
        if(privateContext || privateLabel)return opaque(count,start,end,show)
        val raw=status.firstOrNull { it.number==2 }?.bytes
        val text=raw?.let { bytes ->
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        } ?: if(raw==null)"" else return opaque(count,start,end,show)
        if(status.isEmpty() || start<0 || end<0 || count<0) { detach();return false }
        val value=TvImeValue(text,start,end)
        // Masked contents remain opaque; never turn bullets into a plaintext reconstruction.
        if(text.contains("**") || text.any { it=='\u2022' || it=='\u25cf' || it=='\u25a0' } || !value.valid())return opaque(count,start,end,show)
        if(!show && label.isNotEmpty() && fieldLabel!=null && fieldLabel!=label)detach()
        if(label.isNotEmpty())fieldLabel=label
        safeSelection=start to end
        val flight=pending
        val accepted=flight!=null && count>flight.fieldCounter && value.text==flight.expected.text
        if(show) {
            // Show requests do not carry an atomic field ID. Never ACK/replay an old edit
            // merely because a new field happens to contain the expected text.
            val draft=desired
            val dirty=flight!=null || (desired!=null && desired?.text!=server?.text)
            generation=epochs.incrementAndGet();revision++;externalVersion++;localRevision=0;pending=null;composing=false;active=true
            fieldCounter=count;server=value;desired=if(dirty)draft else value
            mode=if(dirty)TvImeMode.CONFLICT else TvImeMode.LIVE
            return true
        }
        if(!active) { generation=epochs.incrementAndGet();mode=TvImeMode.LIVE }
        active=true
        fieldCounter=count
        val previous=server
        val remoteChanged=server!=value
        if(!accepted && remoteChanged)externalVersion++
        if(flight!=null && !show) {
            if(count<=flight.fieldCounter)return false
            if(accepted) {
                pending=null;server=value
                if(localRevision==flight.localRevision && !composing)desired=value
            } else {
                pending=null;server=value;mode=TvImeMode.CONFLICT
            }
        } else if(accepted) {
            pending=null;server=value
            if(localRevision==flight!!.localRevision && !composing)desired=value
        } else {
            server=value
            val dirtyText=desired!=null && desired?.text!=previous?.text
            if(composing && remoteChanged && dirtyText)mode=TvImeMode.CONFLICT
            else if(desired==null || desired==previous || !dirtyText && remoteChanged) {
                desired=value
                if(remoteChanged)composing=false
            }
            else if(desired?.text!=value.text)mode=TvImeMode.CONFLICT
        }
        revision++
        return show
    }
}
