//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HttpChannel customized to be transported over the HTTP/1 protocol
 */
public class HttpChannelOverHttp extends HttpChannel implements HttpParser.RequestHandler, ComplianceViolation.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannelOverHttp.class);
    private static final HttpField PREAMBLE_UPGRADE_H2C = new HttpField(HttpHeader.UPGRADE, "h2c");
    private final HttpConnection _httpConnection;
    private final RequestBuilder _requestBuilder = new RequestBuilder();
    private MetaData.Request _metadata;
    private HttpField _connection;
    private HttpField _upgrade = null;
    private boolean _delayedForContent;
    private boolean _unknownExpectation = false;
    private boolean _expect100Continue = false;
    private boolean _expect102Processing = false;
    private List<String> _complianceViolations;
    private HttpFields.Mutable _trailers;

    public HttpChannelOverHttp(HttpConnection httpConnection, Connector connector, HttpConfiguration config, EndPoint endPoint, HttpTransport transport)
    {
        super(connector, config, endPoint, transport);
        _httpConnection = httpConnection;
    }

    @Override
    public void abort(Throwable failure)
    {
        super.abort(failure);
        _httpConnection.getGenerator().setPersistent(false);
    }

    @Override
    public void failContent(Throwable failure)
    {
        ((HttpConnection)getEndPoint().getConnection()).failContent(failure);
    }

    @Override
    public void badMessage(BadMessageException failure)
    {
        _httpConnection.getGenerator().setPersistent(false);
        try
        {
            // Need to call onRequest, so RequestLog can reports as much as possible
            if (_metadata == null)
                _metadata = _requestBuilder.build();
            onRequest(_metadata);
            getState().onContent(new HttpInput.ErrorContent(failure));
        }
        catch (Exception e)
        {
            LOG.trace("IGNORED", e);
        }

        onBadMessage(failure);
    }

    @Override
    public boolean content(ByteBuffer content)
    {
        onContent(_httpConnection.newContent(content));
        _delayedForContent = false;
        return true;
    }

    @Override
    public boolean contentComplete()
    {
        boolean handle = onContentComplete() || _delayedForContent;
        _delayedForContent = false;
        return handle;
    }

    /**
     * If the associated response has the Expect header set to 100 Continue,
     * then accessing the input stream indicates that the handler/servlet
     * is ready for the request body and thus a 100 Continue response is sent.
     *
     * @throws IOException if the InputStream cannot be created
     */
    @Override
    public void continue100(int available) throws IOException
    {
        // If the client is expecting 100 CONTINUE, then send it now.
        // TODO: consider using an AtomicBoolean ?
        if (isExpecting100Continue())
        {
            _expect100Continue = false;

            // is content missing?
            if (available == 0)
            {
                if (getResponse().isCommitted())
                    throw new IOException("Committed before 100 Continues");

                boolean committed = sendResponse(HttpGenerator.CONTINUE_100_INFO, null, false);
                if (!committed)
                    throw new IOException("Concurrent commit while trying to send 100-Continue");
            }
        }
    }

    @Override
    public void earlyEOF()
    {
        _httpConnection.getGenerator().setPersistent(false);
        // If we have no request yet, just close
        if (_metadata == null)
            _httpConnection.close();
        else if (onEarlyEOF() || _delayedForContent)
        {
            _delayedForContent = false;
            handle();
        }
    }

    @Override
    public EndPoint getTunnellingEndPoint()
    {
        return getEndPoint();
    }

    @Override
    public boolean headerComplete()
    {
        _metadata = _requestBuilder.build();
        onRequest(_metadata);

        if (_complianceViolations != null && !_complianceViolations.isEmpty())
        {
            this.getRequest().setAttribute(HttpCompliance.VIOLATIONS_ATTR, _complianceViolations);
            _complianceViolations = null;
        }

        boolean persistent;

        switch (_metadata.getHttpVersion())
        {
            case HTTP_0_9:
            {
                persistent = false;
                break;
            }
            case HTTP_1_0:
            {
                if (getHttpConfiguration().isPersistentConnectionsEnabled())
                {
                    if (_connection != null)
                    {
                        if (_connection.contains(HttpHeaderValue.KEEP_ALIVE.asString()))
                            persistent = true;
                        else
                            persistent = _requestBuilder.getFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE.asString());
                    }
                    else
                        persistent = false;
                }
                else
                    persistent = false;

                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.KEEP_ALIVE);

                break;
            }

            case HTTP_1_1:
            {
                if (_unknownExpectation)
                {
                    badMessage(new BadMessageException(HttpStatus.EXPECTATION_FAILED_417));
                    return false;
                }

                if (getHttpConfiguration().isPersistentConnectionsEnabled())
                {
                    if (_connection != null)
                    {
                        if (_connection.contains(HttpHeaderValue.CLOSE.asString()))
                            persistent = false;
                        else
                            persistent = !_requestBuilder.getFields().contains(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString()); // handle multiple connection fields
                    }
                    else
                        persistent = true;
                }
                else
                    persistent = false;

                if (!persistent)
                    persistent = HttpMethod.CONNECT.is(_metadata.getMethod());
                if (!persistent)
                    getResponse().getHttpFields().add(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);

                if (_upgrade != null && upgrade())
                    return true;

                break;
            }

            case HTTP_2:
            {
                // Allow direct "upgrade" to HTTP_2_0 only if the connector supports h2c.
                _upgrade = PREAMBLE_UPGRADE_H2C;

                if (HttpMethod.PRI.is(_metadata.getMethod()) &&
                    "*".equals(_metadata.getURI().getPath()) &&
                    _requestBuilder.getFields().size() == 0 &&
                    upgrade())
                    return true;

                badMessage(new BadMessageException(HttpStatus.UPGRADE_REQUIRED_426));
                _httpConnection.getParser().close();
                return false;
            }

            default:
            {
                throw new IllegalStateException("unsupported version " + _metadata.getHttpVersion());
            }
        }

        if (!persistent)
            _httpConnection.getGenerator().setPersistent(false);

        // Should we delay dispatch until we have some content?
        // We should not delay if there is no content expect or client is expecting 100 or the response is already committed or the request buffer already has something in it to parse
        _delayedForContent = (getHttpConfiguration().isDelayDispatchUntilContent() &&
            (_httpConnection.getParser().getContentLength() > 0 || _httpConnection.getParser().isChunking()) &&
            !isExpecting100Continue() &&
            !isCommitted() &&
            _httpConnection.isRequestBufferEmpty());

        return !_delayedForContent;
    }

    @Override
    public boolean isExpecting100Continue()
    {
        return _expect100Continue;
    }

    @Override
    public boolean isExpecting102Processing()
    {
        return _expect102Processing;
    }

    @Override
    public boolean isTunnellingSupported()
    {
        return true;
    }

    @Override
    public boolean isUseOutputDirectByteBuffers()
    {
        return _httpConnection.isUseOutputDirectByteBuffers();
    }

    @Override
    public boolean messageComplete()
    {
        if (_trailers != null)
            onTrailers(_trailers);
        return onRequestComplete();
    }

    @Override
    public void produceContent()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("produceContent {}", this);
        _httpConnection.parseAndFillForContent();
    }

    @Override
    public void needContent()
    {
        boolean asyncIO = getRequest().getHttpInput().isAsyncIO();
        if (LOG.isDebugEnabled())
            LOG.debug("needContent() asyncIO={}", asyncIO);
        if (asyncIO)
            _httpConnection.asyncReadFillInterested();
        else
            _httpConnection.blockingReadFillInterested();
    }

    @Override
    public void onComplianceViolation(ComplianceViolation.Mode mode, ComplianceViolation violation, String details)
    {
        if (_httpConnection.isRecordHttpComplianceViolations())
        {
            if (_complianceViolations == null)
            {
                _complianceViolations = new ArrayList<>();
            }
            String record = String.format("%s (see %s) in mode %s for %s in %s",
                violation.getDescription(), violation.getURL(), mode, details, getHttpTransport());
            _complianceViolations.add(record);
            if (LOG.isDebugEnabled())
                LOG.debug(record);
        }
    }

    @Override
    public void parsedHeader(HttpField field)
    {
        HttpHeader header = field.getHeader();
        String value = field.getValue();
        if (header != null)
        {
            switch (header)
            {
                case CONNECTION:
                    _connection = field;
                    break;

                case HOST:
                    if (!(field instanceof HostPortHttpField) && value != null && !value.isEmpty())
                        field = new HostPortHttpField(value);
                    break;

                case EXPECT:
                {
                    if (HttpVersion.HTTP_1_1.equals(_requestBuilder.version()))
                    {
                        HttpHeaderValue expect = HttpHeaderValue.CACHE.get(value);
                        switch (expect == null ? HttpHeaderValue.UNKNOWN : expect)
                        {
                            case CONTINUE:
                                _expect100Continue = true;
                                break;

                            case PROCESSING:
                                _expect102Processing = true;
                                break;

                            default:
                                String[] values = field.getValues();
                                for (int i = 0; values != null && i < values.length; i++)
                                {
                                    expect = HttpHeaderValue.CACHE.get(values[i].trim());
                                    if (expect == null)
                                        _unknownExpectation = true;
                                    else
                                    {
                                        switch (expect)
                                        {
                                            case CONTINUE:
                                                _expect100Continue = true;
                                                break;
                                            case PROCESSING:
                                                _expect102Processing = true;
                                                break;
                                            default:
                                                _unknownExpectation = true;
                                        }
                                    }
                                }
                        }
                    }
                    break;
                }

                case UPGRADE:
                    _upgrade = field;
                    break;

                default:
                    break;
            }
        }
        _requestBuilder.getFields().add(field);
    }

    @Override
    public void parsedTrailer(HttpField field)
    {
        if (_trailers == null)
            _trailers = HttpFields.build();
        _trailers.add(field);
    }

    @Override
    public void recycle()
    {
        super.recycle();
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
        _connection = null;
        _upgrade = null;
        _trailers = null;
        _metadata = null;
    }

    @Override
    public void startRequest(String method, String uri, HttpVersion version)
    {
        _requestBuilder.request(method, uri, version);
        _unknownExpectation = false;
        _expect100Continue = false;
        _expect102Processing = false;
    }

    @Override
    protected boolean checkAndPrepareUpgrade()
    {
        // TODO: move the code from HttpConnection.upgrade() here?
        return false;
    }

    @Override
    protected void handleException(Throwable x)
    {
        _httpConnection.getGenerator().setPersistent(false);
        super.handleException(x);
    }

    /**
     * <p>Attempts to perform an HTTP/1.1 upgrade.</p>
     * <p>The upgrade looks up a {@link ConnectionFactory.Upgrading} from the connector
     * matching the protocol specified in the {@code Upgrade} header.</p>
     * <p>The upgrade may succeed, be ignored (which can allow a later handler to implement)
     * or fail with a {@link BadMessageException}.</p>
     *
     * @return true if the upgrade was performed, false if it was ignored
     * @throws BadMessageException if the upgrade failed
     */
    private boolean upgrade() throws BadMessageException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("upgrade {} {}", this, _upgrade);

        @SuppressWarnings("ReferenceEquality")
        boolean isUpgradedH2C = (_upgrade == PREAMBLE_UPGRADE_H2C);

        if (!isUpgradedH2C && (_connection == null || !_connection.contains("upgrade")))
            throw new BadMessageException(HttpStatus.BAD_REQUEST_400);

        // Find the upgrade factory
        ConnectionFactory.Upgrading factory = getConnector().getConnectionFactories().stream()
            .filter(f -> f instanceof ConnectionFactory.Upgrading)
            .map(ConnectionFactory.Upgrading.class::cast)
            .filter(f -> f.getProtocols().contains(_upgrade.getValue()))
            .findAny()
            .orElse(null);

        if (factory == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No factory for {} in {}", _upgrade, getConnector());
            return false;
        }

        // Create new connection
        HttpFields.Mutable response101 = HttpFields.build();
        Connection upgradeConnection = factory.upgradeConnection(getConnector(), getEndPoint(), _metadata, response101);
        if (upgradeConnection == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Upgrade ignored for {} by {}", _upgrade, factory);
            return false;
        }

        // Send 101 if needed
        try
        {
            if (!isUpgradedH2C)
                sendResponse(new MetaData.Response(HttpVersion.HTTP_1_1, HttpStatus.SWITCHING_PROTOCOLS_101, response101, 0), null, true);
        }
        catch (IOException e)
        {
            throw new BadMessageException(HttpStatus.INTERNAL_SERVER_ERROR_500, null, e);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Upgrade from {} to {}", getEndPoint().getConnection(), upgradeConnection);
        getRequest().setAttribute(HttpTransport.UPGRADE_CONNECTION_ATTRIBUTE, upgradeConnection);
        getHttpTransport().onCompleted();
        return true;
    }

    boolean onIdleTimeout(Throwable timeout)
    {
        if (_delayedForContent)
        {
            _delayedForContent = false;
            getRequest().getHttpInput().onIdleTimeout(timeout);
            execute(this);
            return false;
        }
        return true;
    }

    private static class RequestBuilder
    {
        private final HttpFields.Mutable _fieldsBuilder = HttpFields.build();
        private final HttpURI.Mutable _uriBuilder = HttpURI.build();
        private String _method;
        private HttpVersion _version;

        public String method()
        {
            return _method;
        }

        public void request(String method, String uri, HttpVersion version)
        {
            _method = method;
            _uriBuilder.uri(method, uri);
            _version = version;
            _fieldsBuilder.clear();
        }

        public HttpFields.Mutable getFields()
        {
            return _fieldsBuilder;
        }

        public MetaData.Request build()
        {
            return new MetaData.Request(_method, _uriBuilder, _version, _fieldsBuilder);
        }

        public HttpVersion version()
        {
            return _version;
        }
    }
}
