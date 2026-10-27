package com.luma.remote.protocol

import java.util.concurrent.atomic.AtomicLong

/** Cancellation must not wait for a socket writer or the mutable IME state lock. */
internal class ImeSessionFence {
    private val connection=AtomicLong()
    private val invalidThrough=AtomicLong(Long.MIN_VALUE)
    private val session=AtomicLong(Long.MIN_VALUE)
    fun nextConnection():Long=connection.incrementAndGet()
    fun currentConnection():Long=connection.get()
    fun invalidate(session:Long) {invalidThrough.accumulateAndGet(session,::maxOf)}
    fun noteSession(session:Long) {this.session.set(session)}
    fun allows(connection:Long,session:Long)=this.connection.get()==connection && this.session.get()==session && session>invalidThrough.get()
}
