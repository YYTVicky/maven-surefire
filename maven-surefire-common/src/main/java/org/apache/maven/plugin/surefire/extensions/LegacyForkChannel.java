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
import org.apache.maven.surefire.extensions.ForkChannel;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.extensions.util.LineConsumerThread;
import org.apache.maven.surefire.extensions.util.StreamFeeder;
import org.apache.maven.surefire.shared.utils.cli.StreamConsumer;

import javax.annotation.Nonnull;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 *
 */
final class LegacyForkChannel extends ForkChannel
{
    protected LegacyForkChannel( int forkChannelId )
    {
        super( forkChannelId );
    }

    @Override
    public void openChannel()
    {
    }

    @Override
    public String getForkNodeConnectionString()
    {
        return "pipe://" + getForkChannelId();
    }

    @Override
    public boolean useStdIn()
    {
        return true;
    }

    @Override
    public boolean useStdOut()
    {
        return true;
    }

    @Override
    public CloseableDaemonThread bindCommandReader( @Nonnull CommandReader commands,
                                                    @Nonnull WritableByteChannel stdIn )
    {
        return new StreamFeeder( "std-in-fork-" + getForkChannelId(), stdIn, commands );
    }

    @Override
    public CloseableDaemonThread bindCommandReader( @Nonnull CommandReader commands )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public CloseableDaemonThread bindEventHandler( @Nonnull StreamConsumer consumer,
                                                   @Nonnull ReadableByteChannel stdOut,
                                                   @Nonnull CountdownCloseable countdownCloseable )
    {
        return new LineConsumerThread( "std-out-fork-" + getForkChannelId(), stdOut, consumer, countdownCloseable );
    }

    @Override
    public CloseableDaemonThread bindEventHandler( @Nonnull StreamConsumer consumer )
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close()
    {
    }
}
