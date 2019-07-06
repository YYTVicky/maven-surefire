package org.apache.maven.surefire.extensions;

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

import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.shared.utils.cli.StreamConsumer;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * It's a session object used only by a particular Thread in ForkStarter
 * and dedicated forked JVM {@link #getForkChannelId()}. It represents a server.
 * <br>
 * <br>
 * It opens the channel via {@link #openChannel()}, provides a connection string {@link #getForkNodeConnectionString()}
 * used by the client in the JVM, binds event and command handlers.
 *
 * @author <a href="mailto:tibordigana@apache.org">Tibor Digana (tibor17)</a>
 * @since 3.0.0-M5
 */
public abstract class ForkChannel implements Closeable
{
    private final int forkChannelId;

    /**
     * @param forkChannelId the index of the forked JVM, from 1 to N.
     */
    protected ForkChannel( int forkChannelId )
    {
        this.forkChannelId = forkChannelId;
    }

    public abstract void openChannel() throws IOException;

    /**
     * This is server related class, which if binds to a TCP port, determines the connection string for the client.
     *
     * @return a connection string utilized by the client in the fork JVM
     */
    public abstract String getForkNodeConnectionString();

    /**
     * Determines which one of the two <em>bindCommandReader-s</em> to call in <em>ForkStarter</em>.
     * Can be called anytime.
     *
     * @return If {@code true}, calling {@link #bindCommandReader(CommandReader, WritableByteChannel)} by
     * <em>ForkStarter</em> and {@link #bindCommandReader(CommandReader)} throws {@link UnsupportedOperationException}.
     * If {@code false}, then opposite.
     */
    public abstract boolean useStdIn();

    /**
     * Determines which one of the two <em>bindEventHandler-s</em> to call in <em>ForkStarter</em>.
     * Can be called anytime.
     *
     * @return If {@code true}, the {@link #bindEventHandler(StreamConsumer, ReadableByteChannel, CountdownCloseable)}
     * is called in <em>ForkStarter</em> and {@link #bindEventHandler(StreamConsumer)} throws
     * {@link UnsupportedOperationException}.
     * If {@code false}, then opposite.
     */
    public abstract boolean useStdOut();

    /**
     * Binds command handler to the channel.
     *
     * @param commands command reader, see {@link CommandReader#readNextCommand()}
     * @param stdIn    the standard input stream of the JVM to write the encoded commands into it
     * @return the thread instance to start up in order to stream out the data
     * @throws IOException if an error in the fork channel
     */
    public abstract CloseableDaemonThread bindCommandReader( @Nonnull CommandReader commands,
                                                             @Nonnull WritableByteChannel stdIn )
        throws IOException;

    /**
     * Binds command handler to the channel.
     *
     * @param commands command reader, see {@link CommandReader#readNextCommand()}
     * @return the thread instance to start up in order to stream out the data
     * @throws IOException if an error in the fork channel
     */
    public abstract CloseableDaemonThread bindCommandReader( @Nonnull CommandReader commands )
        throws IOException;

    /**
     *
     * @param consumer           event consumer
     * @param stdOut             the standard output stream of the JVM
     * @param countdownCloseable count down of the final call of {@link Closeable#close()}
     * @return the thread instance to start up in order to stream out the data
     * @throws IOException if an error in the fork channel
     */
    public abstract CloseableDaemonThread bindEventHandler( @Nonnull StreamConsumer consumer,
                                                            @Nonnull ReadableByteChannel stdOut,
                                                            @Nonnull CountdownCloseable countdownCloseable )
        throws IOException;

    /**
     * Binds event handler to the channel.
     *
     * @param consumer event consumer
     * @return the thread instance to start up in order to stream out the data
     * @throws IOException if an error in the fork channel
     */
    public abstract CloseableDaemonThread bindEventHandler( @Nonnull StreamConsumer consumer )
        throws IOException;

    /**
     * The index of the fork.
     *
     * @return the index of the forked JVM, from 1 to N.
     */
    protected final int getForkChannelId()
    {
        return forkChannelId;
    }
}
