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

import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestLessInputStream;
import org.apache.maven.plugin.surefire.booterclient.lazytestprovider.TestLessInputStream.TestLessInputStreamBuilder;
import org.apache.maven.plugin.surefire.extensions.SurefireForkNodeFactory;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;

/**
 *
 */
public class ForkChannelTest
{
    private static final long TESTCASE_TIMEOUT = 30_000L;

    private final AtomicBoolean hasError = new AtomicBoolean();

    @Test( timeout = TESTCASE_TIMEOUT )
    public void shouldRequestReplyMessagesViaTCP() throws Exception
    {
        ForkNodeFactory factory = new SurefireForkNodeFactory();
        try ( ForkChannel channel = factory.createForkChannel( 1 ) )
        {
            assertThat( channel.getForkChannelId() )
                .isEqualTo( 1 );

            assertThat( channel.useStdOut() )
                .isFalse();

            assertThat( channel.getForkNodeConnectionString() )
                .startsWith( "tcp://127.0.0.1:" )
                .isNotEqualTo( "tcp://127.0.0.1:" );

            URI uri = new URI( channel.getForkNodeConnectionString() );

            assertThat( uri.getPort() )
                .isPositive();

            Consumer consumer = new Consumer();

            Client client = new Client( uri.getPort() );
            client.start();

            channel.connectToClient();
            SECONDS.sleep( 3L );

            TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
            TestLessInputStream commandReader = builder.build();

            channel.bindCommandReader( commandReader, null ).start();

            final AtomicBoolean isCloseableCalled = new AtomicBoolean();
            Closeable closeable = new Closeable()
            {
                @Override
                public void close()
                {
                    isCloseableCalled.set( true );
                }
            };
            CountdownCloseable cc = new CountdownCloseable( closeable, 1 );
            channel.bindEventHandler( consumer, cc, null ).start();

            SECONDS.sleep( 3L );

            commandReader.noop();

            SECONDS.sleep( 3L );

            client.join( TESTCASE_TIMEOUT );

            assertThat( hasError.get() )
                .isFalse();

            assertThat( isCloseableCalled.get() )
                .isTrue();

            assertThat( consumer.lines )
                .hasSize( 1 )
                .containsOnly( "Hi There!" );
        }
    }

    private static class Consumer implements EventHandler
    {
        final Queue<String> lines = new ConcurrentLinkedQueue<>();

        @Override
        public void handleEvent( @Nonnull String s )
        {
            lines.add( s );
        }
    }

    private final class Client extends Thread
    {
        private final int port;

        private Client( int port )
        {
            this.port = port;
        }

        @Override
        public void run()
        {
            try ( Socket socket = new Socket( "127.0.0.1", port ) )
            {
                byte[] data = new byte[128];
                int readLength = socket.getInputStream().read( data );
                String token = new String( data, 0, readLength, US_ASCII );
                assertThat( token ).isEqualTo( ":maven-surefire-command:noop:" );
                socket.getOutputStream().write( "Hi There!".getBytes( US_ASCII ) );
            }
            catch ( IOException e )
            {
                hasError.set( true );
                e.printStackTrace();
                throw new IllegalStateException( e );
            }
            catch ( RuntimeException e )
            {
                hasError.set( true );
                e.printStackTrace();
                throw e;
            }
        }
    }
}
