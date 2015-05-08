package org.nustaq.kontraktor.asyncio;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.util.Log;

import java.io.IOException;
import java.net.SocketOption;
import java.net.SocketOptions;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Created by ruedi on 04/05/15.
 */
public class AsyncServerSocket {

    ServerSocketChannel socket;
    Selector selector;
    SelectionKey serverkey;
    BiFunction<SelectionKey,SocketChannel,AsyncServerSocketConnection> connectionFactory;

    public void connect( int port, BiFunction<SelectionKey,SocketChannel,AsyncServerSocketConnection> connectionFactory ) throws IOException {
        selector = Selector.open();
        socket = ServerSocketChannel.open();
        socket.configureBlocking(false);
        socket.socket().bind(new java.net.InetSocketAddress(port));
        serverkey = socket.register(selector, SelectionKey.OP_ACCEPT);
        this.connectionFactory = connectionFactory;
        receiveLoop();
    }

    public void receiveLoop() {
        Actor actor = Actor.sender.get();
        if ( actor == null )
        {
            throw new RuntimeException("only usable from within an actor");
        }
        boolean hadStuff = false;
        try {
            selector.selectNow();
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            for (Iterator<SelectionKey> iterator = selectionKeys.iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                try {
                    if (key == serverkey) {
                        if (key.isAcceptable()) {
                            SocketChannel accept = socket.accept();
                            if (accept != null) {
                                hadStuff = true;
                                accept.configureBlocking(false);
                                SelectionKey newKey = accept.register(selector, SelectionKey.OP_READ|SelectionKey.OP_WRITE);
                                AsyncServerSocketConnection con = connectionFactory.apply(key, accept);
                                newKey.attach(con);
                            }
                        }
                    } else {
                        SocketChannel client = (SocketChannel) key.channel();
                        if (key.isWritable()) {
                            AsyncServerSocketConnection con = (AsyncServerSocketConnection) key.attachment();
                            ByteBuffer writingBuffer = con.getWritingBuffer();
                            if ( writingBuffer != null ) {
                                int written = con.chan.write(writingBuffer);
                                if (written<0) {
                                    // closed
                                    con.finishWrite();
                                } else
                                if ( writingBuffer.remaining() == 0 ) {
                                    con.finishWrite();
                                    iterator.remove();
                                }
                            }
                        }
                        if (key.isReadable()) {
                            iterator.remove();
                            AsyncServerSocketConnection con = (AsyncServerSocketConnection) key.attachment();
                            if ( con == null ) {
                                Log.Lg.warn(this, "con is null " + key);
                            } else {
                                hadStuff = true;
                                try {
                                    con.readData();
                                } catch (Exception ioe) {
                                    con.closed(ioe);
                                    key.cancel();
                                    try {
                                        client.close();
                                    } catch (IOException e) {
                                        Log.Warn(this, e);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.Warn(this,e,"");
                }
            }
        } catch (Throwable e) {
            Log.Warn(this,e,"");
            Actors.reject(e);
        }
        if ( ! isClosed() ) {
            if ( hadStuff ) {
                actor.execute( () -> receiveLoop() );
            } else {
                actor.delayed( 2, () -> receiveLoop() );
            }
        }
    }

    public boolean isClosed() {
        return false;
    }

    public void close() throws IOException {
        socket.close();
    }
}
