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

import org.apache.maven.surefire.extensions.CloseableDaemonThread;
import org.apache.maven.surefire.extensions.CommandReader;
import org.apache.maven.surefire.extensions.EventHandler;
import org.apache.maven.surefire.extensions.ForkChannel;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.extensions.util.LineConsumerThread;
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
 *
 */
final class SurefireForkChannel extends ForkChannel
{
    private static final byte[] LOCAL_LOOPBACK_IP_ADDRESS = new byte[]{127, 0, 0, 1};

    private final ServerSocketChannel server;
    private final int serverPort;
    private SocketChannel channel;

    SurefireForkChannel( int forkChannelId ) throws IOException
    {
        super( forkChannelId );
        server = open();
        setTrueOptions( SO_REUSEADDR, TCP_NODELAY, SO_KEEPALIVE );
        InetAddress ip = Inet4Address.getByAddress( LOCAL_LOOPBACK_IP_ADDRESS );
        server.bind( new InetSocketAddress( ip, 0 ), 1 );
        serverPort = ( (InetSocketAddress) server.getLocalAddress() ).getPort();
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
        return "tcp://127.0.0.1:" + serverPort;
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
    public CloseableDaemonThread bindEventHandler( @Nonnull EventHandler eventHandler,
                                                   @Nonnull CountdownCloseable countdownCloseable,
                                                   ReadableByteChannel stdOut )
    {
        // todo develop Event and EventConsumerThread, see the algorithm in ForkedChannelDecoder#handleEvent()
        return new LineConsumerThread( "events-fork-" + getForkChannelId(), channel,
            eventHandler, countdownCloseable );
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
