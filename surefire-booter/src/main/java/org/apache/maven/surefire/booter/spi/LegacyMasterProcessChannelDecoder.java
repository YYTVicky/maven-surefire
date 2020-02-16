package org.apache.maven.surefire.booter.spi;

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
import org.apache.maven.surefire.providerapi.MasterProcessChannelDecoder;

import javax.annotation.Nonnull;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.apache.maven.surefire.booter.MasterProcessCommand.MAGIC_NUMBER;

/**
 * magic number : opcode [: opcode specific data]*
 * <br>
 *
 * @author <a href="mailto:tibordigana@apache.org">Tibor Digana (tibor17)</a>
 * @since 3.0.0-M5
 */
public class LegacyMasterProcessChannelDecoder implements MasterProcessChannelDecoder
{
    private final InputStream is;

    public LegacyMasterProcessChannelDecoder( @Nonnull InputStream is )
    {
        this.is = is;
    }

    @Override
    @Nonnull
    @SuppressWarnings( "checkstyle:innerassignment" )
    public Command decode() throws IOException
    {
        List<String> tokens = new ArrayList<>( 3 );
        StringBuilder token = new StringBuilder( MAGIC_NUMBER.length() );
        boolean endOfStream;

        start:
        do
        {
            tokens.clear();
            token.setLength( 0 );
            boolean frameStarted = false;
            for ( int r; !( endOfStream = ( r = is.read() ) == -1 ) ; )
            {
                char c = (char) r;
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
                        FrameCompletion completion = isFrameComplete( tokens );
                        if ( completion == FrameCompletion.COMPLETE )
                        {
                            break;
                        }
                        else if ( completion == FrameCompletion.MALFORMED )
                        {
                            continue start;
                        }
                    }
                    else
                    {
                        token.append( c );
                    }
                }
            }

            if ( isFrameComplete( tokens ) == FrameCompletion.COMPLETE )
            {
                MasterProcessCommand cmd = MasterProcessCommand.byOpcode( tokens.get( 1 ) );
                if ( tokens.size() == 2 )
                {
                    return new Command( cmd );
                }
                else if ( tokens.size() == 3 )
                {
                    return new Command( cmd, tokens.get( 2 ) );
                }
            }

            if ( endOfStream )
            {
                throw new EOFException();
            }
        }
        while ( true );
    }

    private FrameCompletion isFrameComplete( List<String> tokens )
    {
        if ( !tokens.isEmpty() && !MAGIC_NUMBER.equals( tokens.get( 0 ) ) )
        {
            return FrameCompletion.MALFORMED;
        }

        if ( tokens.size() >= 2 )
        {
            String opcode = tokens.get( 1 );
            MasterProcessCommand cmd = MasterProcessCommand.byOpcode( opcode );
            if ( cmd == null )
            {
                return FrameCompletion.MALFORMED;
            }
            else if ( cmd.hasDataType() == ( tokens.size() == 3 ) )
            {
                return FrameCompletion.COMPLETE;
            }
        }
        return FrameCompletion.NOT_COMPLETE;
    }

    @Override
    public void close()
    {
    }

    /**
     * Determines whether the token is complete of malformed.
     */
    private enum FrameCompletion
    {
        NOT_COMPLETE,
        COMPLETE,
        MALFORMED
    }
}
