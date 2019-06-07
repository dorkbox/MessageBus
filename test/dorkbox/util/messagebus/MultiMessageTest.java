/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.messageBus.DispatchMode;
import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.SubscriptionMode;
import dorkbox.messageBus.annotations.Subscribe;
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
        MessageBus bus = new MessageBus(DispatchMode.Exact, SubscriptionMode.StrongReferences,
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
        MessageBus bus = new MessageBus(DispatchMode.ExactWithSuperTypes, SubscriptionMode.StrongReferences,
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
        @Subscribe
        public void handleSync(Object o) {
            count.getAndIncrement();
            System.err.println("match Object");
        }

        @Subscribe
        public void handleSync(String o1) {
            count.getAndIncrement();
            System.err.println("match String");
        }

        @Subscribe
        public void handleSync(String o1, String o2) {
            count.getAndIncrement();
            System.err.println("match String, String");
        }

        @Subscribe
        public void handleSync(String o1, String o2, String o3) {
            count.getAndIncrement();
            System.err.println("match String, String, String");
        }

        @Subscribe
        public void handleSync(Integer o1, Integer o2, String o3) {
            count.getAndIncrement();
            System.err.println("match Integer, Integer, String");
        }

        @Subscribe
        public void handleSync(String... o) {
            count.getAndIncrement();
            System.err.println("match String[]");
        }

        @Subscribe
        public void handleSync(Integer... o) {
            count.getAndIncrement();
            System.err.println("match Integer[]");
        }

        @Subscribe
        public void handleSync(Object... o) {
            count.getAndIncrement();
            System.err.println("match Object[]");
        }
    }
}
