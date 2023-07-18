/*
 * Copyright 2019 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus.publication;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.conversantmedia.util.concurrent.DisruptorBlockingQueue;
import com.conversantmedia.util.concurrent.SpinPolicy;
import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.messageBus.error.ErrorHandler;
import dorkbox.messageBus.subscription.asm.AsmInvocation;
import dorkbox.messageBus.subscription.reflection.ReflectionInvocation;
import dorkbox.util.NamedThreadFactory;

public
class ConversantDisruptor implements Publisher {

    private final Publisher syncPublisher;
    private final ThreadPoolExecutor threadExecutor;
    private final DisruptorBlockingQueue<Runnable> workQueue;

    public
    ConversantDisruptor(final int numberOfThreads) {
        this.syncPublisher = new DirectInvocation();

        // ALWAYS round to the nearest power of 2
        int minQueueCapacity = 1 << (32 - Integer.numberOfLeadingZeros(numberOfThreads));

        workQueue = new DisruptorBlockingQueue<>(minQueueCapacity, SpinPolicy.WAITING);
        threadExecutor = new ThreadPoolExecutor(numberOfThreads, numberOfThreads,
                                                0L, TimeUnit.MILLISECONDS, workQueue,
                                                new NamedThreadFactory("MessageBus",  true));
    }

    public
    ConversantDisruptor(final ConversantDisruptor publisher) {
        this.syncPublisher = publisher.syncPublisher;
        this.threadExecutor = publisher.threadExecutor;
        this.workQueue = publisher.workQueue;
    }


    // ASM
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message) {
        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, handler, handleIndex, message);
            }
        });
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1,
                 final Object message2) {

        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, handler, handleIndex, message1, message2);
            }
        });
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final AsmInvocation invocation, final Object listener, final MethodAccess handler, final int handleIndex,
                 final Object message1, final Object message2, final Object message3) {

        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, handler, handleIndex, message1, message2, message3);
            }
        });
    }



    // REFLECTION
    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message) {

        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, method, message);
            }
        });
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2) {

        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, method, message1, message2);
            }
        });
    }

    @Override
    public
    void publish(final ErrorHandler errorHandler,
                 final ReflectionInvocation invocation, final Object listener, final Method method,
                 final Object message1, final Object message2, final Object message3) {

        threadExecutor.submit(new Runnable() {
            @Override
            public
            void run() {
                syncPublisher.publish(errorHandler,invocation, listener, method, message1, message2, message3);
            }
        });
    }

    @Override
    public
    boolean hasPendingMessages() {
        return threadExecutor.getActiveCount() > 0 || workQueue.isEmpty();
    }

    @Override
    public
    void shutdown() {
        // This uses Thread.interrupt()
        threadExecutor.shutdownNow();
    }
}
