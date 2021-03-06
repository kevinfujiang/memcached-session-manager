/*
 * Copyright 2009 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;

import org.apache.catalina.Globals;
import org.apache.catalina.Valve;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.http.ServerCookie;
import org.jmock.Mock;
import org.jmock.cglib.MockObjectTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * Test the {@link SessionTrackerValve}.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionTrackerValveTest extends MockObjectTestCase {

    private Mock _sessionBackupServiceControl;
    private SessionBackupService _service;
    private SessionTrackerValve _sessionTrackerValve;
    private Mock _nextValve;
    private Mock _requestControl;
    private Request _request;
    private Response _response;
    private Mock _responseControl;

    @BeforeMethod
    public void setUp() throws Exception {
        _sessionBackupServiceControl = mock( SessionBackupService.class );
        _service = (SessionBackupService) _sessionBackupServiceControl.proxy();
        _sessionTrackerValve = new SessionTrackerValve( null, new StandardContext(), _service, Statistics.create(), new AtomicBoolean(true) );
        _nextValve = mock( Valve.class );
        _sessionTrackerValve.setNext( (Valve) _nextValve.proxy() );

        _requestControl = mock( Request.class );
        _request = (Request) _requestControl.proxy();
        _responseControl = mock( Response.class );
        _response = (Response) _responseControl.proxy();
    }

    @AfterMethod
    public void tearDown() throws Exception {
        _sessionBackupServiceControl.reset();
        _nextValve.reset();
        _requestControl.reset();
        _responseControl.reset();
    }

    private void verifyMocks() {
        _sessionBackupServiceControl.verify();
        _nextValve.verify();
        _requestControl.verify();
        _responseControl.verify();
    }

    @Test
    public final void testSessionCookieName() throws IOException, ServletException {
        final StandardContext context = new StandardContext();
        context.setSessionCookieName( "foo" );
        SessionTrackerValve cut = new SessionTrackerValve( null, context, _service, Statistics.create(), new AtomicBoolean(true) );
        assertEquals( "foo", cut.getSessionCookieName() );

        context.setSessionCookieName( null );
        cut = new SessionTrackerValve( null, context, _service, Statistics.create(), new AtomicBoolean(true) );
        assertEquals( Globals.SESSION_COOKIE_NAME, cut.getSessionCookieName() );
    }

    @Test
    public final void testBackupSessionNotInvokedWhenNoSessionIdPresent() throws IOException, ServletException {
        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _nextValve.expects( once() ).method( "invoke" );
        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _responseControl.expects( once() ).method( "getHeader" ).with( eq( "Set-Cookie" ) ).will( returnValue( null ) );

        _sessionBackupServiceControl.expects( never() ).method( "backupSession" );

        _sessionTrackerValve.invoke( _request, _response );

        verifyMocks();
    }

    @Test
    public final void testBackupSessionInvokedWhenResponseCookiePresent() throws IOException, ServletException {

        _requestControl.expects( once() ).method( "getRequestedSessionId" ).will( returnValue( null ) );
        _nextValve.expects( once() ).method( "invoke" );

        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), "foo" );
        _responseControl.expects( once() ).method( "getHeader" ).with( eq( "Set-Cookie" ) )
            .will( returnValue( generateCookieString( cookie ) ) );
        _requestControl.expects( once() ).method( "getRequestURI" ).will( returnValue( "/someRequest" ) );
        _requestControl.expects( once() ).method( "getMethod" ).will( returnValue( "GET" ) );
        _requestControl.expects( once() ).method( "getQueryString" ).will( returnValue( null ) );
        _sessionBackupServiceControl.expects( once() ).method( "backupSession" ).with( eq( "foo" ), eq( false), ANYTHING )
            .will( returnValue( null ) );
        _sessionTrackerValve.invoke( _request, _response );

        verifyMocks();

    }

    private String generateCookieString(final Cookie cookie) {
        final StringBuffer sb = new StringBuffer();
        ServerCookie.appendCookieValue
        (sb, cookie.getVersion(), cookie.getName(), cookie.getValue(),
             cookie.getPath(), cookie.getDomain(), cookie.getComment(),
             cookie.getMaxAge(), cookie.getSecure(), true );
        final String setSessionCookieHeader = sb.toString();
        return setSessionCookieHeader;
    }

    @Test
    public final void testBackupSessionInvokedWhenSessionExisting() throws IOException, ServletException {

        final String sessionId = "foo";
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdOnTomcatFailover" ).with( eq( sessionId)  );
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdOnMemcachedFailover" ).with( eq( sessionId)  );
        _requestControl.expects( atLeastOnce() ).method( "getRequestedSessionId" ).will( returnValue( sessionId ) );
        _nextValve.expects( once() ).method( "invoke" );

        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), sessionId );
        _responseControl.expects( once() ).method( "getHeader" ).with( eq( "Set-Cookie" ) )
            .will( returnValue( generateCookieString( cookie ) ) );
        _requestControl.expects( once() ).method( "getRequestURI" ).will( returnValue( "/foo" ) );
        _requestControl.expects( once() ).method( "getMethod" ).will( returnValue( "GET" ) );
        _requestControl.expects( once() ).method( "getQueryString" );
        _sessionBackupServiceControl.expects( once() ).method( "backupSession" ).with( eq( sessionId ), eq( false), ANYTHING )
            .will( returnValue( null ) );

        _sessionTrackerValve.invoke( _request, _response );

        verifyMocks();

    }

    @Test
    public final void testChangeSessionIdForRelocatedSession() throws IOException, ServletException {

        final String sessionId = "bar";
        final String newSessionId = "newId";

        _requestControl.expects( atLeastOnce() ).method( "getRequestedSessionId" ).will( returnValue( sessionId ) );
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdOnTomcatFailover" ).with( eq( sessionId )  );
        _sessionBackupServiceControl.expects( once() ).method( "changeSessionIdOnMemcachedFailover" ).with( eq( sessionId )  ).will( returnValue( newSessionId ) );

        _requestControl.expects( once() ).method( "changeSessionId" ).with( eq( newSessionId ) );

        _nextValve.expects( once() ).method( "invoke" );

        final Cookie cookie = new Cookie( _sessionTrackerValve.getSessionCookieName(), newSessionId );
        _responseControl.expects( once() ).method( "getHeader" ).with( eq( "Set-Cookie" ) )
            .will( returnValue( generateCookieString( cookie ) ) );

        _requestControl.expects( once() ).method( "getRequestURI" ).will( returnValue( "/foo" ) );
        _requestControl.expects( once() ).method( "getMethod" ).will( returnValue( "GET" ) );
        _requestControl.expects( once() ).method( "getQueryString" );
        _sessionBackupServiceControl.expects( once() ).method( "backupSession" ).with( eq( newSessionId ), eq( true ), ANYTHING )
            .will( returnValue( null ) );

        _sessionTrackerValve.invoke( _request, _response );

        verifyMocks();

    }

}
