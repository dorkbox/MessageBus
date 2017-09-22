/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.messageBus.IMessageBus;
import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.annotations.Handler;
import dorkbox.util.messagebus.common.MessageBusTest;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
@SuppressWarnings("Duplicates")
public class MultiMessageTest extends MessageBusTest {

    private static AtomicInteger count = new AtomicInteger(0);

    @Test
    public void testMultiMessageSendingExact() {
        IMessageBus bus = new MessageBus(IMessageBus.DispatchMode.Exact,
                                         Runtime.getRuntime()
                                                .availableProcessors() / 2);
        MultiListener listener1 = new MultiListener();
        bus.subscribe(listener1);
        bus.unsubscribe(listener1);

        bus.publish("s");
        bus.publish("s", "s");
        bus.publish("s", "s", "s");
        bus.publish(1, "s");
        bus.publish(1, 2, "s");
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(0, count.get());

        bus.subscribe(listener1);

        bus.publish("s"); // 1
        bus.publish("s", "s"); // 1
        bus.publish("s", "s", "s"); // 1
        bus.publish(1, "s"); // 0
        bus.publish(1, 2, "s"); // 1
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6}); // 1

        assertEquals(5, count.get());
        count.set(0);
    }

    @Test
    public void testMultiMessageSendingExactAndSuper() {
        IMessageBus bus = new MessageBus(IMessageBus.DispatchMode.ExactWithSuperTypes,
                                         Runtime.getRuntime()
                                                .availableProcessors() / 2);
        MultiListener listener1 = new MultiListener();
        bus.subscribe(listener1);
        bus.unsubscribe(listener1);

        bus.publish("s");
        bus.publish("s", "s");
        bus.publish("s", "s", "s");
        bus.publish(1, "s");
        bus.publish(1, 2, "s");
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(0, count.get());

        bus.subscribe(listener1);

        bus.publish("s"); // 2
        bus.publish("s", "s"); // 1
        bus.publish("s", "s", "s"); // 1
        bus.publish(1, "s"); // 0
        bus.publish(1, 2, "s"); // 1
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6}); // 2

        assertEquals(7, count.get());
        count.set(0);
    }

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

        @Handler
        public void handleSync(String o1, String o2) {
            count.getAndIncrement();
            System.err.println("match String, String");
        }

        @Handler
        public void handleSync(String o1, String o2, String o3) {
            count.getAndIncrement();
            System.err.println("match String, String, String");
        }

        @Handler
        public void handleSync(Integer o1, Integer o2, String o3) {
            count.getAndIncrement();
            System.err.println("match Integer, Integer, String");
        }

        @Handler
        public void handleSync(String... o) {
            count.getAndIncrement();
            System.err.println("match String[]");
        }

        @Handler
        public void handleSync(Integer... o) {
            count.getAndIncrement();
            System.err.println("match Integer[]");
        }

        @Handler
        public void handleSync(Object... o) {
            count.getAndIncrement();
            System.err.println("match Object[]");
        }
    }
}
