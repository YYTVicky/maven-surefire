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

import org.apache.commons.codec.binary.Base64;
import org.apache.maven.plugin.surefire.booterclient.output.DeserializedStacktraceWriter;
import org.apache.maven.plugin.surefire.log.api.ConsoleLogger;
import org.apache.maven.surefire.booter.ForkedProcessEventType;
import org.apache.maven.surefire.eventapi.ConsoleDebugEvent;
import org.apache.maven.surefire.eventapi.ConsoleErrorEvent;
import org.apache.maven.surefire.eventapi.ConsoleInfoEvent;
import org.apache.maven.surefire.eventapi.ConsoleWarningEvent;
import org.apache.maven.surefire.eventapi.ControlByeEvent;
import org.apache.maven.surefire.eventapi.ControlNextTestEvent;
import org.apache.maven.surefire.eventapi.ControlStopOnNextTestEvent;
import org.apache.maven.surefire.eventapi.Event;
import org.apache.maven.surefire.eventapi.JvmExitErrorEvent;
import org.apache.maven.surefire.eventapi.StandardStreamErrEvent;
import org.apache.maven.surefire.eventapi.StandardStreamErrWithNewLineEvent;
import org.apache.maven.surefire.eventapi.StandardStreamOutEvent;
import org.apache.maven.surefire.eventapi.StandardStreamOutWithNewLineEvent;
import org.apache.maven.surefire.eventapi.SystemPropertyEvent;
import org.apache.maven.surefire.eventapi.TestAssumptionFailureEvent;
import org.apache.maven.surefire.eventapi.TestErrorEvent;
import org.apache.maven.surefire.eventapi.TestFailedEvent;
import org.apache.maven.surefire.eventapi.TestSkippedEvent;
import org.apache.maven.surefire.eventapi.TestStartingEvent;
import org.apache.maven.surefire.eventapi.TestSucceededEvent;
import org.apache.maven.surefire.eventapi.TestsetCompletedEvent;
import org.apache.maven.surefire.eventapi.TestsetStartingEvent;
import org.apache.maven.surefire.extensions.CloseableDaemonThread;
import org.apache.maven.surefire.extensions.EventHandler;
import org.apache.maven.surefire.extensions.util.CountdownCloseable;
import org.apache.maven.surefire.report.ReportEntry;
import org.apache.maven.surefire.report.RunMode;
import org.apache.maven.surefire.report.StackTraceWriter;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.apache.maven.surefire.booter.ForkedProcessEventType.MAGIC_NUMBER;
import static org.apache.maven.surefire.report.CategorizedReportEntry.reportEntry;
import static org.apache.maven.surefire.report.RunMode.MODES;
import static org.apache.maven.surefire.shared.utils.StringUtils.isBlank;

/**
 *
 */
public class EventConsumerThread extends CloseableDaemonThread
{
    private static final Base64 BASE64 = new Base64();

    private final ReadableByteChannel channel;
    private final EventHandler<Event> eventHandler;
    private final CountdownCloseable countdownCloseable;
    private final ConsoleLogger logger;
    private volatile boolean disabled;

    protected EventConsumerThread( @Nonnull String threadName,
                                   @Nonnull ReadableByteChannel channel,
                                   @Nonnull EventHandler<Event> eventHandler,
                                   @Nonnull CountdownCloseable countdownCloseable,
                                   @Nonnull ConsoleLogger logger )
    {
        super( threadName );
        this.channel = channel;
        this.eventHandler = eventHandler;
        this.countdownCloseable = countdownCloseable;
        this.logger = logger;
    }

    @Override
    public void run()
    {
        try ( ReadableByteChannel stream = channel;
              CountdownCloseable c = countdownCloseable; )
        {
            decode();
        }
        catch ( IOException e )
        {
            // not needed
        }
    }

    @Override
    public void disable()
    {
        disabled = true;
    }

    @Override
    public void close() throws IOException
    {
        channel.close();
    }

    @SuppressWarnings( "checkstyle:innerassignment" )
    private void decode() throws IOException
    {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder( MAGIC_NUMBER.length() );
        ByteBuffer buffer = ByteBuffer.allocate( 1 );
        boolean endOfStream;

        start:
        do
        {
            tokens.clear();
            token.setLength( 0 );
            FrameCompletion completion = null;
            for ( boolean frameStarted = false; !( endOfStream = channel.read( buffer ) == -1 ) ; completion = null )
            {
                buffer.flip();
                char c = (char) buffer.get();
                buffer.clear();

                if ( !frameStarted )
                {
                    if ( c == ':' )
                    {
                        frameStarted = true;
                        token.setLength( 0 );
                        tokens.clear();
                    }
                }
                else
                {
                    if ( c == ':' )
                    {
                        tokens.add( token.toString() );
                        token.setLength( 0 );
                        completion = frameCompleteness( tokens );
                        if ( completion == FrameCompletion.COMPLETE )
                        {
                            break;
                        }
                        else if ( completion == FrameCompletion.MALFORMED )
                        {
                            logger.error( "Malformed frame with tokens " + tokens );
                            continue start;
                        }
                    }
                    else
                    {
                        token.append( c );
                    }
                }
            }

            if ( completion == FrameCompletion.COMPLETE )
            {
                Event event = toEvent( tokens );
                if ( !disabled && event != null )
                {
                    eventHandler.handleEvent( event );
                }
            }

            if ( endOfStream )
            {
                return;
            }
        }
        while ( true );
    }

    private Event toEvent( List<String> tokensInFrame )
    {
        Iterator<String> tokens = tokensInFrame.iterator();
        assert tokens.next() != null;

        ForkedProcessEventType event = ForkedProcessEventType.byOpcode( tokens.next() );

        if ( event == null )
        {
            return null;
        }

        if ( event.isControlCategory() )
        {
            switch ( event )
            {
                case BOOTERCODE_BYE:
                    return new ControlByeEvent();
                case BOOTERCODE_STOP_ON_NEXT_TEST:
                    return new ControlStopOnNextTestEvent();
                case BOOTERCODE_NEXT_TEST:
                    return new ControlNextTestEvent();
                default:
                    throw new IllegalStateException( "Unknown enum " + event );
            }
        }
        else if ( event.isConsoleCategory() )
        {
            Charset encoding = Charset.forName( tokens.next() );
            String msg = decode( tokens.next(), encoding );
            switch ( event )
            {
                case BOOTERCODE_CONSOLE_INFO:
                    return new ConsoleInfoEvent( msg );
                case BOOTERCODE_CONSOLE_DEBUG:
                    return new ConsoleDebugEvent( msg );
                case BOOTERCODE_CONSOLE_WARNING:
                    return new ConsoleWarningEvent( msg );
                case BOOTERCODE_CONSOLE_ERROR:
                    String smartStackTrace = decode( tokens.next(), encoding );
                    String stackTrace = decode( tokens.next(), encoding );
                    StackTraceWriter stackTraceWriter = decodeTrace( encoding, msg, smartStackTrace, stackTrace );
                    return new ConsoleErrorEvent( stackTraceWriter );
                default:
                    throw new IllegalStateException( "Unknown enum " + event );
            }
        }
        else if ( event.isStandardStreamCategory() )
        {
            RunMode mode = MODES.get( tokens.next() );
            Charset encoding = Charset.forName( tokens.next() );
            String output = decode( tokens.next(), encoding );
            switch ( event )
            {
                case BOOTERCODE_STDOUT:
                    return new StandardStreamOutEvent( mode, output );
                case BOOTERCODE_STDOUT_NEW_LINE:
                    return new StandardStreamOutWithNewLineEvent( mode, output );
                case BOOTERCODE_STDERR:
                    return new StandardStreamErrEvent( mode, output );
                case BOOTERCODE_STDERR_NEW_LINE:
                    return new StandardStreamErrWithNewLineEvent( mode, output );
                default:
                    throw new IllegalStateException( "Unknown enum " + event );
            }
        }
        else if ( event.isSysPropCategory() )
        {
            RunMode mode = MODES.get( tokens.next() );
            Charset encoding = Charset.forName( tokens.next() );
            String key = decode( tokens.next(), encoding );
            String value = decode( tokens.next(), encoding );
            return new SystemPropertyEvent( mode, key, value );
        }
        else if ( event.isTestCategory() )
        {
            RunMode mode = MODES.get( tokens.next() );
            Charset encoding = Charset.forName( tokens.next() );
            String sourceName = tokens.next();
            String sourceText = tokens.next();
            String name = tokens.next();
            String nameText = tokens.next();
            String group = tokens.next();
            String message = tokens.next();
            String elapsed = tokens.next();
            String traceMessage = tokens.next();
            String smartTrimmedStackTrace = tokens.next();
            String stackTrace = tokens.next();
            ReportEntry reportEntry = toReportEntry( encoding, sourceName, sourceText, name, nameText,
                group, message, elapsed, traceMessage, smartTrimmedStackTrace, stackTrace );
            switch ( event )
            {
                case BOOTERCODE_TESTSET_STARTING:
                    return new TestsetStartingEvent( mode, reportEntry );
                case BOOTERCODE_TESTSET_COMPLETED:
                    return new TestsetCompletedEvent( mode, reportEntry );
                case BOOTERCODE_TEST_STARTING:
                    return new TestStartingEvent( mode, reportEntry );
                case BOOTERCODE_TEST_SUCCEEDED:
                    return new TestSucceededEvent( mode, reportEntry );
                case BOOTERCODE_TEST_FAILED:
                    return new TestFailedEvent( mode, reportEntry );
                case BOOTERCODE_TEST_SKIPPED:
                    return new TestSkippedEvent( mode, reportEntry );
                case BOOTERCODE_TEST_ERROR:
                    return new TestErrorEvent( mode, reportEntry );
                case BOOTERCODE_TEST_ASSUMPTIONFAILURE:
                    return new TestAssumptionFailureEvent( mode, reportEntry );
                default:
                    throw new IllegalStateException( "Unknown enum " + event );
            }
        }
        else if ( event.isJvmExitError() )
        {
            Charset encoding = Charset.forName( tokens.next() );
            String message = decode( tokens.next(), encoding );
            String smartTrimmedStackTrace = decode( tokens.next(), encoding );
            String stackTrace = decode( tokens.next(), encoding );
            StackTraceWriter stackTraceWriter = decodeTrace( encoding, message, smartTrimmedStackTrace, stackTrace );
            return new JvmExitErrorEvent( stackTraceWriter );
        }

        throw new IllegalStateException( "Missing a branch for the event type " + event );
    }

    private static FrameCompletion frameCompleteness( List<String> tokens )
    {
        if ( !tokens.isEmpty() && !MAGIC_NUMBER.equals( tokens.get( 0 ) ) )
        {
            return FrameCompletion.MALFORMED;
        }

        if ( tokens.size() >= 2 )
        {
            String opcode = tokens.get( 1 );
            ForkedProcessEventType event = ForkedProcessEventType.byOpcode( opcode );
            if ( event == null )
            {
                return FrameCompletion.MALFORMED;
            }
            else if ( event.isControlCategory() )
            {
                return FrameCompletion.COMPLETE;
            }
            else if ( event.isConsoleErrorCategory() )
            {
                return tokens.size() == 6 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
            else if ( event.isConsoleCategory() )
            {
                return tokens.size() == 4 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
            else if ( event.isStandardStreamCategory() )
            {
                return tokens.size() == 5 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
            else if ( event.isSysPropCategory() )
            {
                return tokens.size() == 6 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
            else if ( event.isTestCategory() )
            {
                return tokens.size() == 14 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
            else if ( event.isJvmExitError() )
            {
                return tokens.size() == 6 ? FrameCompletion.COMPLETE : FrameCompletion.NOT_COMPLETE;
            }
        }
        return FrameCompletion.NOT_COMPLETE;
    }

    private static String decode( String line, Charset encoding )
    {
        // ForkedChannelEncoder is encoding the stream with US_ASCII
        return line == null || "-".equals( line )
            ? null
            : new String( BASE64.decode( line.getBytes( US_ASCII ) ), encoding );
    }

    private static StackTraceWriter decodeTrace( Charset encoding, String encTraceMessage,
                                                 String encSmartTrimmedStackTrace, String encStackTrace )
    {
        if ( isBlank( encStackTrace ) || "-".equals( encStackTrace ) )
        {
            return null;
        }
        else
        {
            String traceMessage = decode( encTraceMessage, encoding );
            String stackTrace = decode( encStackTrace, encoding );
            String smartTrimmedStackTrace = decode( encSmartTrimmedStackTrace, encoding );
            return new DeserializedStacktraceWriter( traceMessage, smartTrimmedStackTrace, stackTrace );
        }
    }

    private static ReportEntry toReportEntry( Charset encoding,
                                      // ReportEntry:
                                      String encSource, String encSourceText, String encName, String encNameText,
                                      String encGroup, String encMessage, String encTimeElapsed,
                                      // StackTraceWriter:
                                      String encTraceMessage, String encSmartTrimmedStackTrace, String encStackTrace )
        throws NumberFormatException
    {
        if ( encoding == null )
        {
            // corrupted or incomplete stream
            return null;
        }

        String source = decode( encSource, encoding );
        String sourceText = decode( encSourceText, encoding );
        String name = decode( encName, encoding );
        String nameText = decode( encNameText, encoding );
        String group = decode( encGroup, encoding );
        StackTraceWriter stackTraceWriter =
            decodeTrace( encoding, encTraceMessage, encSmartTrimmedStackTrace, encStackTrace );
        Integer elapsed = decodeToInteger( encTimeElapsed );
        String message = decode( encMessage, encoding );
        return reportEntry( source, sourceText, name, nameText,
            group, stackTraceWriter, elapsed, message, Collections.<String, String>emptyMap() );
    }

    private static Integer decodeToInteger( String line )
    {
        return line == null || "-".equals( line ) ? null : Integer.decode( line );
    }

    /**
     * Determines whether the frame is complete or malformed.
     */
    private enum FrameCompletion
    {
        NOT_COMPLETE,
        COMPLETE,
        MALFORMED
    }
}
