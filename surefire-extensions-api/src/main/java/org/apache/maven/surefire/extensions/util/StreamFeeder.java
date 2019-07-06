package org.apache.maven.surefire.extensions.util;

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

import org.apache.maven.surefire.booter.Command;
import org.apache.maven.surefire.booter.MasterProcessCommand;
import org.apache.maven.surefire.extensions.CloseableDaemonThread;
import org.apache.maven.surefire.extensions.CommandReader;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.WritableByteChannel;

/**
 *
 */
public class StreamFeeder extends CloseableDaemonThread
{
    private final WritableByteChannel channel;
    private final CommandReader commandReader;

    private volatile boolean disabled;
    private volatile Throwable exception;

    public StreamFeeder( @Nonnull String threadName, @Nonnull WritableByteChannel channel,
                         @Nonnull CommandReader commandReader )
    {
        super( threadName );
        this.channel = channel;
        this.commandReader = commandReader;
    }

    @Override
    @SuppressWarnings( "checkstyle:innerassignment" )
    public void run()
    {
        try ( WritableByteChannel c = channel )
        {
            for ( Command cmd; ( cmd = commandReader.readNextCommand() ) != null; )
            {
                if ( !disabled )
                {
                    MasterProcessCommand cmdType = cmd.getCommandType();
                    byte[] data = cmdType.hasDataType() ? cmdType.encode( cmd.getData() ) : cmdType.encode();
                    c.write( ByteBuffer.wrap( data ) );
                }
            }
        }
        catch ( ClosedChannelException e )
        {
            // closed externally
        }
        catch ( IOException | NonWritableChannelException e )
        {
            exception = e.getCause() == null ? e : e.getCause();
        }
    }

    public void disable()
    {
        disabled = true;
    }

    public Throwable getException()
    {
        return exception;
    }

    @Override
    public void close() throws IOException
    {
        channel.close();
    }
}
