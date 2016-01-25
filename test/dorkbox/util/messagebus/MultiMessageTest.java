/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.MessageBusTest;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MultiMessageTest extends MessageBusTest {

    private static AtomicInteger count = new AtomicInteger(0);

    @Test
    public void testMultiMessageSending() {
        IMessageBus bus = new MessageBus(IMessageBus.PublishMode.ExactWithSuperTypesAndVarity, Runtime.getRuntime().availableProcessors() / 2);
//        IMessageBus bus = new MessageBus();  // non varity mode
        bus.start();

        MultiListener listener1 = new MultiListener();
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);
        bus.subscribe(listener1);

        bus.unsubscribe(listener1);
        bus.unsubscribe(listener1);
        bus.unsubscribe(listener1);
        bus.unsubscribe(listener1);

        bus.publish("s");
        bus.publish("s");
        bus.publish("s");
        bus.publish("s");
        bus.publish("s", "s");
        bus.publish("s", "s", "s");
        bus.publish(1, "s");
        bus.publish(1, 2, "s");
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(0, count.get());

        bus.subscribe(listener1);

        bus.publish("s"); // 4
        bus.publish("s", "s"); // 3
        bus.publish("s", "s", "s"); // 3
        bus.publish(1, "s"); // 1
        bus.publish(1, 2, "s"); // 2
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6}); // 2

        assertEquals(15, count.get());
        count.set(0);


        // test async publication
        bus.publishAsync("s");
        bus.publishAsync("s", "s");
        bus.publishAsync("s", "s", "s");
        bus.publish(1, "s");
        bus.publishAsync(1, 2, "s");
        bus.publishAsync(new Integer[] {1, 2, 3, 4, 5, 6});

        while (bus.hasPendingMessages()) {
            try {
                Thread.sleep(ConcurrentUnits);
            } catch (InterruptedException ignored) {
            }
        }

        assertEquals(13, count.get());


        bus.shutdown();
    }

    @SuppressWarnings("unused")
    public static class MultiListener {
        @Handler
        public void handleSync(Object o) {
            count.getAndIncrement();
            System.err.println("match Object");
        }

        @Handler
        public void handleSync(String o1) {
            count.getAndIncrement();
            System.err.println("match String");
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
//        @Handler(acceptVarargs = true)
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
//
//        @Handler(acceptVarargs = true)
//        public void handleSync(Object... o) {
//            count.getAndIncrement();
//            System.err.println("match Object[]");
//        }
    }
}
