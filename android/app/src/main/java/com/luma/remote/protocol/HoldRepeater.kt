package com.luma.remote.protocol

import kotlinx.coroutines.*

internal val repeatingCommands=setOf(Command.UP,Command.DOWN,Command.LEFT,Command.RIGHT,Command.VOLUME_UP,Command.VOLUME_DOWN,Command.CHANNEL_UP,Command.CHANNEL_DOWN,Command.REWIND,Command.FORWARD,Command.DELETE)
// OK needs a duration, never a stream of repeated selections on tap-only providers.
internal val pressCommands=repeatingCommands+Command.OK

/** Sends serially, without a queue: slow transports never accumulate repeated commands. */
internal class HoldRepeater(private val scope:CoroutineScope,private val initialDelay:Long=400,private val interval:Long=130) {
    private var owner:Any?=null
    private var job:Job?=null
    fun start(token:Any,allowed:()->Boolean,repeatInterval:Long=interval,repeat:Boolean=true,send:suspend ()->Unit) {
        val previous=job
        stop(); owner=token
        job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            if(!allowed())return@launch
            send()
            if(!repeat)return@launch
            delay(initialDelay)
            while(isActive && owner===token && allowed()) { send(); delay(repeatInterval) }
        }
    }
    /** Preserve real DOWN/UP lifetimes for receivers whose cursor advances between frames. */
    fun startNative(token:Any,allowed:()->Boolean,begin:suspend ()->Unit,end:suspend ()->Unit,onError:(Exception)->Unit={}) {
        val previous=job
        stop();owner=token
        job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            var began=false
            try {
                withContext(NonCancellable) { previous?.join() }
                ensureActive()
                if(!allowed())return@launch
                began=true
                begin()
                awaitCancellation()
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { onError(e) }
            finally {
                if(began)withContext(NonCancellable) {
                    try { end() } catch(e:Exception) { onError(e) }
                }
            }
        }
    }
    fun stop(token:Any?=null) {
        if(token!=null && owner!==token)return
        // Keep the cleanup task: a release followed by a new press must await UP.
        owner=null; job?.cancel()
    }
}
