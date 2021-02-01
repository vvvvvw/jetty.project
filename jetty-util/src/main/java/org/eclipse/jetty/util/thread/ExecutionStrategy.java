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

package org.eclipse.jetty.util.thread;

/**
 * <p>An {@link ExecutionStrategy} executes {@link Runnable} tasks produced by a {@link Producer}.
 * The strategy to execute the task may vary depending on the implementation; the task may be
 * run in the calling thread, or in a new thread, etc.</p>
 * <p>The strategy delegates the production of tasks to a {@link Producer}, and continues to
 * execute tasks until the producer continues to produce them.</p>
 */
public interface ExecutionStrategy
{
    /**
     * <p>Initiates (or resumes) the task production and consumption.</p>
     * <p>This method guarantees that the task is never run by the
     * thread that called this method.</p>
     *
     * TODO review the need for this (only used by HTTP2 push)
     *
     * @see #produce()
     */
    //目前只在HTTP2中用到
    public void dispatch();

    /**
     * <p>Initiates (or resumes) the task production and consumption.</p>
     * <p>The produced task may be run by the same thread that called
     * this method.</p>
     *
     * @see #dispatch()
     */
    //实现具体执行策略，任务生产出来后可以由当前线程执行，也可能由新线程来执行
    public void produce();

    /**
     * <p>A producer of {@link Runnable} tasks to run.</p>
     * <p>The {@link ExecutionStrategy} will repeatedly invoke {@link #produce()} until
     * the producer returns null, indicating that it has nothing more to produce.</p>
     * <p>When no more tasks can be produced, implementations should arrange for the
     * {@link ExecutionStrategy} to be invoked again in case an external event resumes
     * the tasks production.</p>
     */
    //Runnable任务的生产接口。
    ////ExecutionStrategy将会不断调用#produce（）方法生成任务并执行，直到producer返回null（表示producer已经没有更多需要生产的东西了）
    //当没有更多任务需要生产而返回时，ExecutionStrategy会在外部事件恢复时再次调用produce方法生产任务。
    public interface Producer
    {
        /**
         * 任务生产方法
         * <p>Produces a task to be executed.</p>
         *
         * @return a task to executed or null if there are no more tasks to execute
         */
        //生产一个Runnable(任务)
        Runnable produce();
    }
}
