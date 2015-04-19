/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import junit.framework.Assert;
import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ConcurrentExecutor;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class PerformanceTest {
    // 15 == 32 * 1024
    public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;

    public static final int QUEUE_CAPACITY = 1 << Integer.getInteger("pow2.capacity", 17);

    public static final int CONCURRENCY_LEVEL = 2;

    private static long count = 0;

    protected static final IPublicationErrorHandler TestFailingHandler = new IPublicationErrorHandler() {
        @Override
        public void handleError(PublicationError error) {
            error.getCause().printStackTrace();
            Assert.fail();
        }
    };

    public static void main(String[] args) throws Exception {
        final MultiMBassador bus = new MultiMBassador(CONCURRENCY_LEVEL);
        bus.addErrorHandler(TestFailingHandler);


        Listener listener1 = new Listener();
        bus.subscribe(listener1);


        ConcurrentExecutor.runConcurrent(new Runnable() {
            @Override
            public void run() {
                long num = 0;
                while (num < Long.MAX_VALUE) {
                    bus.publishAsync(num++);
                }
            }}, CONCURRENCY_LEVEL);


        bus.shutdown();
        System.err.println("Count: " + count);
    }

    public PerformanceTest() {
    }

    @SuppressWarnings("unused")
    public static class Listener {
        @Handler
        public void handleSync(Long o1) {
//            System.err.println(Long.toString(o1));
              count++;
        }
    }
}
