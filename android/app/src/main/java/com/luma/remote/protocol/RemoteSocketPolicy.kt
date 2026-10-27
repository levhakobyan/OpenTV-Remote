package com.luma.remote.protocol

import java.net.Socket

internal object RemoteSocketPolicy {
    fun handshake(socket:Socket) { socket.soTimeout=10_000 }
    // Remote service events need not arrive periodically. Closing the socket cancels a blocked read.
    fun ready(socket:Socket) { socket.soTimeout=0; socket.keepAlive=true }
}
