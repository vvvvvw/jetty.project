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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

/**
 * ScopedHandler.
 *
 * A ScopedHandler is a HandlerWrapper where the wrapped handlers
 * each define a scope.
 *
 * <p>When {@link #handle(String, Request, HttpServletRequest, HttpServletResponse)}
 * is called on the first ScopedHandler in a chain of HandlerWrappers,
 * the {@link #doScope(String, Request, HttpServletRequest, HttpServletResponse)} method is
 * called on all contained ScopedHandlers, before the
 * {@link #doHandle(String, Request, HttpServletRequest, HttpServletResponse)} method
 * is called on all contained handlers.</p>
 *
 * <p>For example if Scoped handlers A, B &amp; C were chained together, then
 * the calling order would be:</p>
 * <pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *              C.doHandle(...)
 * </pre>
 *
 * <p>If non scoped handler X was in the chained A, B, X &amp; C, then
 * the calling order would be:</p>
 * <pre>
 * A.handle(...)
 *   A.doScope(...)
 *     B.doScope(...)
 *       C.doScope(...)
 *         A.doHandle(...)
 *           B.doHandle(...)
 *             X.handle(...)
 *               C.handle(...)
 *                 C.doHandle(...)
 * </pre>
 *
 * <p>A typical usage pattern is:</p>
 * <pre>
 *     private static class MyHandler extends ScopedHandler
 *     {
 *         public void doScope(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 setUpMyScope();
 *                 super.doScope(target,request,response);
 *             }
 *             finally
 *             {
 *                 tearDownMyScope();
 *             }
 *         }
 *
 *         public void doHandle(String target, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
 *         {
 *             try
 *             {
 *                 doMyHandling();
 *                 super.doHandle(target,request,response);
 *             }
 *             finally
 *             {
 *                 cleanupMyHandling();
 *             }
 *         }
 *     }
 * </pre>
 */
public abstract class ScopedHandler extends HandlerWrapper
{
    private static final ThreadLocal<ScopedHandler> __outerScope = new ThreadLocal<ScopedHandler>(); //这个全局 threadlocal的作用，辅助_outerScope的生成，因为_outerScope这个变量不能通过方法参数在 Handler 链中进行传递，但是在形成链的过程中又需要用到它，所以直接设置成全局变量就都能拿到了（构建完调用链的_outerScope，这个字段会被重新设置为null）
    protected ScopedHandler _outerScope; //_outScope指向的是当前 Handler 链的第一个ScopedHandler节点，该节点本身_outScope为 null
    protected ScopedHandler _nextScope; //指向下一个 Scoped 节点的引用（注意是 Scoped节点，如果直接下游不是Scoped节点，则指向下游的下游的Scoped节点，反正就只能指向Scoped节点，当到达链尾的时候，_nextScope指向的是null）

    //注意：调用doStart方法的时候 handler调用链 已经构建完了，doStart方法主要是设置 _outerScope和_nextScope变量
    @Override
    protected void doStart() throws Exception
    {
        try
        {
            //请注意_outScope是一个实例变量，而__outerScope是一个全局变量。先读取全局的线程私有变量__outerScope到_outerScope中
            _outerScope = __outerScope.get();
            if (_outerScope == null) //如果全局的__outerScope还没有被赋值，说明执行doStart方法的是头节点
                __outerScope.set(this); //handler链的头节点将自己的引用填充到__outerScope

            super.doStart(); //递归调用下一个handle的doStart方法（调用父类HandlerWrapper的doStart方法，最终会调用到 下一个HandlerWrapper保存的下一个handler实例的start方法）

            _nextScope = getChildHandlerByClass(ScopedHandler.class); //获取下游第一个类型为ScopedHandler的handler，并将该handler赋值给_nextScope（对于链尾，没有下游，返回null）
        }
        finally
        {
            if (_outerScope == null) //只有第一个节点的 _outerScope为null
                __outerScope.set(null); //将ThreadLocal 变量__outerScope设置为 null，原因：本次处理结束，为了下次同一个线程处理时，还能正常的设置第一个scope handler，必须把threadlocal变量设为null

        }
    }

    @Override
    public final void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (isStarted())
        {   //_outerScope为 null（说明自己是第一个ScopedHandler节点），调用doScope方法
            if (_outerScope == null)
                doScope(target, baseRequest, request, response);
            else //
                doHandle(target, baseRequest, request, response); //_outerScope不为 null（说明自己不是第一个ScopedHandler节点），调用doHandle方法
        }
    }

    /**
     * Scope the handler
     * <p>Derived implementations should call {@link #nextScope(String, Request, HttpServletRequest, HttpServletResponse)}
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        nextScope(target, baseRequest, request, response);
    }

    /**
     * Scope the handler
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public final void nextScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        if (_nextScope != null) //如果还有ScopedHandler的下游节点，则调用 下游节点的doScope方法
            _nextScope.doScope(target, baseRequest, request, response);
        else if (_outerScope != null) //如果自己不是第一个ScopedHandler节点，则调用 第一个ScopedHandler的doScope方法
            _outerScope.doHandle(target, baseRequest, request, response);
        else //如果没有了ScopedHandler的下游节点且自己是第一个ScopedHandler节点 ，则调用 第一个ScopedHandler的doScope方法
            doHandle(target, baseRequest, request, response);
    }

    /**
     * Do the handler work within the scope.
     * <p>Derived implementations should call {@link #nextHandle(String, Request, HttpServletRequest, HttpServletResponse)}
     *
     * @param target The target of the request - either a URI or a name.
     * @param baseRequest The original unwrapped request object.
     * @param request The request either as the {@link Request} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     * method can be used access the Request object if required.
     * @param response The response as the {@link Response} object or a wrapper of that request. The
     * <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     * method can be used access the Response object if required.
     * @throws IOException if unable to handle the request or response processing
     * @throws ServletException if unable to handle the request or response due to underlying servlet issue
     */
    public abstract void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException;

    /*
     * Do the handler work within the scope.
     * @param target
     *          The target of the request - either a URI or a name.
     * @param baseRequest
     *          The original unwrapped request object.
     * @param request
     *            The request either as the {@link Request} object or a wrapper of that request. The
     *            <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getRequest() getRequest()}</code>
     *            method can be used access the Request object if required.
     * @param response
     *            The response as the {@link Response} object or a wrapper of that request. The
     *            <code>{@link HttpConnection#getCurrentConnection()}.{@link HttpConnection#getHttpChannel() getHttpChannel()}.{@link HttpChannel#getResponse() getResponse()}</code>
     *            method can be used access the Response object if required.
     * @throws IOException
     *             if unable to handle the request or response processing
     * @throws ServletException
     *             if unable to handle the request or response due to underlying servlet issue
     */
    //留给子类的 doHandle方法使用，如果有下游节点且下游是ScopedHandler，则调用下游的doHandle方法，否则调用下游的handle方法
    public final void nextHandle(String target, final Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (_nextScope != null && _nextScope == _handler)
            _nextScope.doHandle(target, baseRequest, request, response);
        else if (_handler != null)
            super.handle(target, baseRequest, request, response);
    }
}
