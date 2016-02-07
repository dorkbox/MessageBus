/*
 * Copyright 2015 dorkbox, llc
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
package dorkbox.messagebus.common;

import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class NamedThreadFactory implements ThreadFactory {
    /**
     * The stack size is arbitrary based on JVM implementation. Default is 0
     * 8k is the size of the android stack. Depending on the version of android, this can either change, or will always be 8k
     * <p/>
     * To be honest, 8k is pretty reasonable for an asynchronous/event based system (32bit) or 16k (64bit)
     * Setting the size MAY or MAY NOT have any effect!!!
     * <p/>
     * Stack size must be specified in bytes. Default is 8k
     */
    private static final long stackSizeForThreads;

    static {
        String stackSize = null;

        {
            RuntimeMXBean runtimeMX = java.lang.management.ManagementFactory.getRuntimeMXBean();
            List<String> inputArguments = runtimeMX.getInputArguments();

            Locale english = Locale.ENGLISH;
            for (String xss : inputArguments) {
                String xssLower = xss.toLowerCase(english);
                if (xssLower.startsWith("-xss")) {
                    stackSize = xssLower;
                    break;
                }
            }
        }

        if (stackSize != null) {
            int value = 0;
            if (stackSize.endsWith("k")) {
                stackSize = stackSize.substring(4, stackSize.length() - 1);
                value = Integer.parseInt(stackSize) * 1024;
            }
            else if (stackSize.endsWith("m")) {
                stackSize = stackSize.substring(4, stackSize.length() - 1);
                value = Integer.parseInt(stackSize) * 1024 * 1024;
            }
            else {
                try {
                    value = Integer.parseInt(stackSize.substring(4));
                } catch (Exception ignored) {
                }
            }

            stackSizeForThreads = value;
        }
        else {
            stackSizeForThreads = 8192;
        }
    }

    private final AtomicInteger threadID = new AtomicInteger(0);
    private final ThreadGroup group;
    private final String groupName;

    public
    NamedThreadFactory(String groupName) {
        this.groupName = groupName;
        this.group = new ThreadGroup(groupName);
    }

    @Override
    public
    Thread newThread(Runnable r) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(this.groupName);
        stringBuilder.append('-');
        stringBuilder.append(this.threadID.getAndIncrement());


        return newThread(stringBuilder.toString(), r);
    }

    private
    Thread newThread(String name, Runnable r) {
        // stack size is arbitrary based on JVM implementation. Default is 0
        // 8k is the size of the android stack. Depending on the version of android, this can either change, or will always be 8k
        // To be honest, 8k is pretty reasonable for an asynchronous/event based system (32bit) or 16k (64bit)
        // Setting the size MAY or MAY NOT have any effect!!!
        Thread t = new Thread(this.group, r, name, NamedThreadFactory.stackSizeForThreads);
        t.setDaemon(true);// FORCE these threads to finish before allowing the JVM to exit
        if (t.getPriority() != Thread.NORM_PRIORITY) {
            t.setPriority(Thread.NORM_PRIORITY);
        }
        return t;
    }
}

