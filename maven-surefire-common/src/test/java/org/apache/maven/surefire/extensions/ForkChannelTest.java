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
import org.apache.maven.surefire.shared.utils.cli.StreamConsumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 *
 */
@RunWith( PowerMockRunner.class )
@PowerMockIgnore( { "org.jacoco.agent.rt.*", "com.vladium.emma.rt.*" } )
public class ForkChannelTest
{
    private static final long TESTCASE_TIMEOUT = 30_000L;

    @Mock
    private StreamConsumer consumer;

    @Test( timeout = TESTCASE_TIMEOUT )
    public void shouldRequestReplyMessagesViaTCP() throws Exception
    {
        ForkNodeFactory factory = new SurefireForkNodeFactory();
        ForkChannel channel = factory.createForkChannel( 1 );

        assertThat( channel.getForkChannelId() )
            .isEqualTo( 1 );

        assertThat( channel.useStdIn() )
            .isFalse();

        assertThat( channel.useStdOut() )
            .isFalse();

        assertThat( channel.getForkNodeConnectionString() )
            .startsWith( "tcp://127.0.0.1:" )
            .isNotEqualTo( "tcp://127.0.0.1:" );

        URI uri = new URI( channel.getForkNodeConnectionString() );

        assertThat( uri.getPort() )
            .isPositive();

        ArgumentCaptor<String> line = ArgumentCaptor.forClass( String.class );
        doNothing().when( consumer ).consumeLine( anyString() );

        Client client = new Client( uri.getPort() );
        final AtomicBoolean hasError = new AtomicBoolean();
        client.setUncaughtExceptionHandler( new UncaughtExceptionHandler()
        {
            @Override
            public void uncaughtException( Thread t, Throwable e )
            {
                hasError.set( true );
                e.printStackTrace( System.err );
            }
        } );
        client.start();

        channel.connectToClient();
        SECONDS.sleep( 3L );

        TestLessInputStreamBuilder builder = new TestLessInputStreamBuilder();
        TestLessInputStream commandReader = builder.build();
        channel.bindCommandReader( commandReader ).start();
        channel.bindEventHandler( consumer ).start();

        SECONDS.sleep( 3L );

        commandReader.noop();

        client.join( TESTCASE_TIMEOUT );

        assertThat( hasError.get() )
            .isFalse();

        verify( consumer, times( 1 ) )
            .consumeLine( line.capture() );

        assertThat( line.getValue() )
            .isEqualTo( "Hi There!" );
    }

    private static class Client extends Thread
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
                throw new IllegalStateException( e );
            }
        }
    }
}
