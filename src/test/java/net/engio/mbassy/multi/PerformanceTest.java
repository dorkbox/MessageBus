/*
 * Copyright 2015 dorkbox, llc
 */
package net.engio.mbassy.multi;

import junit.framework.Assert;
import net.engio.mbassy.multi.annotations.Handler;
import net.engio.mbassy.multi.error.IPublicationErrorHandler;
import net.engio.mbassy.multi.error.PublicationError;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class PerformanceTest {

    private static long count = 0;

    protected static final IPublicationErrorHandler TestFailingHandler = new IPublicationErrorHandler() {
        @Override
        public void handleError(PublicationError error) {
            error.getCause().printStackTrace();
            Assert.fail();
        }
    };

    public static void main(String[] args) {
        PerformanceTest multiMessageTest = new PerformanceTest();
        multiMessageTest.testMultiMessageSending();
    }


    public PerformanceTest() {
    }

    public void testMultiMessageSending() {
        MultiMBassador bus = new MultiMBassador();
        bus.addErrorHandler(TestFailingHandler);


        Listener listener1 = new Listener();
        bus.subscribe(listener1);

        long num = Long.MAX_VALUE;
        while (num-- > 0) {
            bus.publish("s");
        }

//        bus.publish("s", "s");
//        bus.publish("s", "s", "s");
//        bus.publish("s", "s", "s", "s");
//        bus.publish(1, 2, "s");
//        bus.publish(1, 2, 3, 4, 5, 6);
//        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        bus.shutdown();
        System.err.println("Count: " + count);
    }

    @SuppressWarnings("unused")
    public static class Listener {
        @Handler
        public void handleSync(String o1) {
            count++;
//            System.err.println("match String");
        }

//        @Handler
//        public void handleSync(String o1, String o2) {
//            count.getAndIncrement();
//            System.err.println("match String, String");
//        }
//
//        @Handler
//        public void handleSync(String o1, String o2, String o3) {
//            count.getAndIncrement();
//            System.err.println("match String, String, String");
//        }
//
//        @Handler
//        public void handleSync(Integer o1, Integer o2, String o3) {
//            count.getAndIncrement();
//            System.err.println("match Integer, Integer, String");
//        }
//
//        @Handler
//        public void handleSync(String... o) {
//            count.getAndIncrement();
//            System.err.println("match String[]");
//        }
//
//        @Handler
//        public void handleSync(Integer... o) {
//            count.getAndIncrement();
//            System.err.println("match Integer[]");
//        }
    }
}
