package org.apache.maven.plugin.surefire.extensions;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.plugin.surefire.log.api.ConsoleLogger;
import org.apache.maven.surefire.eventapi.Event;
import org.apache.maven.surefire.extensions.CloseableDaemonThread;
import org.apache.maven.surefire.extensions.CommandReader;
import org.apache.maven.surefire.extensions.EventHandler;
import org.apache.maven.surefire.extensions.ForkChannel;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.extensions.util.StreamFeeder;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.channels.Channel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

import static java.net.StandardSocketOptions.SO_KEEPALIVE;
import static java.net.StandardSocketOptions.SO_REUSEADDR;
import static java.net.StandardSocketOptions.TCP_NODELAY;
import static java.nio.channels.ServerSocketChannel.open;

/**
 * The TCP/IP server accepting only one client connection. The forked JVM connects to the server using the
 * {@link #getForkNodeConnectionString() connection string}.
 * The main purpose of this class is to {@link #connectToClient() conect with tthe client}, bind the
 * {@link #bindCommandReader(CommandReader, WritableByteChannel) command reader} to the internal socket's
 * {@link java.io.InputStream}, and bind the
 * {@link #bindEventHandler(EventHandler, CountdownCloseable, ReadableByteChannel) event handler} writing the event
 * objects to the {@link EventHandler event handler}.
 * <br>
 * The objects {@link WritableByteChannel} and {@link ReadableByteChannel} are forked process streams
 * (standard input and output). Both are ignored in this implementation but they are used in {@link LegacyForkChannel}.
 * <br>
 * The channel is closed after the forked JVM has finished normally or the shutdown hook is executed in the plugin.
 */
final class SurefireForkChannel extends ForkChannel
{
    private static final byte[] LOCAL_LOOPBACK_IP_ADDRESS = new byte[]{127, 0, 0, 1};

    private final ConsoleLogger logger;
    private final ServerSocketChannel server;
    private final int localPort;
    private volatile SocketChannel channel;

    SurefireForkChannel( int forkChannelId, ConsoleLogger logger ) throws IOException
    {
        super( forkChannelId );
        this.logger = logger;
        server = open();
        setTrueOptions( SO_REUSEADDR, TCP_NODELAY, SO_KEEPALIVE );
        InetAddress ip = Inet4Address.getByAddress( LOCAL_LOOPBACK_IP_ADDRESS );
        server.bind( new InetSocketAddress( ip, 0 ), 1 );
        localPort = ( (InetSocketAddress) server.getLocalAddress() ).getPort();
    }

    @Override
    public void connectToClient() throws IOException
    {
        if ( channel != null )
        {
            throw new IllegalStateException( "already accepted TCP client connection" );
        }
        channel = server.accept();
    }

    @SafeVarargs
    private final void setTrueOptions( SocketOption<Boolean>... options ) throws IOException
    {
        for ( SocketOption<Boolean> option : options )
        {
            if ( server.supportedOptions().contains( option ) )
            {
                server.setOption( option, true );
            }
        }
    }

    @Override
    public String getForkNodeConnectionString()
    {
        return "tcp://127.0.0.1:" + localPort;
    }

    @Override
    public boolean useStdOut()
    {
        return false;
    }

    @Override
    public CloseableDaemonThread bindCommandReader( @Nonnull CommandReader commands,
                                                    WritableByteChannel stdIn )
    {
        return new StreamFeeder( "commands-fork-" + getForkChannelId(), channel, commands );
    }

    @Override
    public CloseableDaemonThread bindEventHandler( @Nonnull EventHandler<Event> eventHandler,
                                                   @Nonnull CountdownCloseable countdownCloseable,
                                                   ReadableByteChannel stdOut )
    {
        return new EventConsumerThread( "fork-" + getForkChannelId() + "-event-thread-", channel,
            eventHandler, countdownCloseable, logger );
    }

    @Override
    public void close() throws IOException
    {
        //noinspection EmptyTryBlock
        try ( Channel c1 = channel; Channel c2 = server )
        {
            // only close all channels
        }
    }
}
