/*
 * Copyright 2010 dorkbox, llc
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
package dorkbox.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The default thread factory with names.
 */
public
class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger poolId = new AtomicInteger();

    private final ThreadGroup group;
    private final String namePrefix;
    private final int threadPriority;
    private final boolean daemon;

    /**
     * Creates a DAEMON thread
     */
    public
    NamedThreadFactory(String poolNamePrefix) {
        this(poolNamePrefix, Thread.currentThread().getThreadGroup(), Thread.MAX_PRIORITY, true);
    }

    public
    NamedThreadFactory(String poolNamePrefix, boolean isDaemon) {
        this(poolNamePrefix, Thread.currentThread().getThreadGroup(), Thread.MAX_PRIORITY, isDaemon);
    }


    /**
     * Creates a DAEMON thread
     */
    public
    NamedThreadFactory(String poolNamePrefix, ThreadGroup group) {
        this(poolNamePrefix, group, Thread.MAX_PRIORITY, true);
    }

    public
    NamedThreadFactory(String poolNamePrefix, ThreadGroup group, boolean isDaemon) {
        this(poolNamePrefix, group, Thread.MAX_PRIORITY, isDaemon);
    }

    /**
     * Creates a DAEMON thread
     */
    public
    NamedThreadFactory(String poolNamePrefix, int threadPriority) {
        this(poolNamePrefix, threadPriority, true);
    }

    public
    NamedThreadFactory(String poolNamePrefix, int threadPriority, boolean isDaemon) {
        this(poolNamePrefix, null, threadPriority, isDaemon);
    }

    /**
     * @param poolNamePrefix what you want the subsequent threads to be named.
     * @param group the group this thread will belong to. If NULL, it will belong to the current thread group.
     * @param threadPriority 1-10, with 5 being normal and 10 being max
     */
    public
    NamedThreadFactory(String poolNamePrefix, ThreadGroup group, int threadPriority, boolean isDaemon) {
        this.daemon = isDaemon;
        this.namePrefix = poolNamePrefix;
        if (group == null) {
            this.group = Thread.currentThread().getThreadGroup();
        }
        else {
            this.group = group;
        }


        if (threadPriority < Thread.MIN_PRIORITY) {
            throw new IllegalArgumentException(String.format("Thread priority (%s) must be >= %s", threadPriority, Thread.MIN_PRIORITY));
        }
        if (threadPriority > Thread.MAX_PRIORITY) {
            throw new IllegalArgumentException(String.format("Thread priority (%s) must be <= %s", threadPriority, Thread.MAX_PRIORITY));
        }


        this.threadPriority = threadPriority;
    }

    @Override
    public
    Thread newThread(Runnable r) {
        // stack size is arbitrary based on JVM implementation. Default is 0
        // 8k is the size of the android stack. Depending on the version of android, this can either change, or will always be 8k
        // To be honest, 8k is pretty reasonable for an asynchronous/event based system (32bit) or 16k (64bit)
        // Setting the size MAY or MAY NOT have any effect!!!
        Thread t = new Thread(this.group, r, this.namePrefix + '-' + this.poolId.incrementAndGet());
        t.setDaemon(this.daemon);
        if (t.getPriority() != this.threadPriority) {
            t.setPriority(this.threadPriority);
        }
        return t;
    }
}
