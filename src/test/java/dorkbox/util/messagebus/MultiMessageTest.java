/*
 * Copyright 2015 dorkbox, llc
 */
package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.MessageBusTest;

/**
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MultiMessageTest extends MessageBusTest {

    private static AtomicInteger count = new AtomicInteger(0);

    @Test
    public void testMultiMessageSending(){
        IMessageBus bus = new MultiMBassador();

        Listener listener1 = new Listener();
        bus.subscribe(listener1);
        bus.unsubscribe(listener1);

        bus.publish("s");
        bus.publish("s", "s");
        bus.publish("s", "s", "s");
        bus.publish(1, 2, "s");
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(0, count.get());

        bus.subscribe(listener1);

        bus.publish("s");
        bus.publish("s", "s");
        bus.publish("s", "s", "s");
        bus.publish(1, 2, "s");
        bus.publish(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(12, count.get());
        count.set(0);


        bus.publishAsync("s");
        bus.publishAsync("s", "s");
        bus.publishAsync("s", "s", "s");
        bus.publishAsync(1, 2, "s");
        bus.publishAsync(new Integer[] {1, 2, 3, 4, 5, 6});

        assertEquals(12, count.get());


        bus.shutdown();
    }

    @SuppressWarnings("unused")
    public static class Listener {
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

        @Handler(acceptVarargs=true)
        public void handleSync(String... o) {
            count.getAndIncrement();
            System.err.println("match String[]");
        }

        @Handler
        public void handleSync(Integer... o) {
            count.getAndIncrement();
            System.err.println("match Integer[]");
        }

        @Handler(acceptVarargs=true)
        public void handleSync(Object... o) {
            count.getAndIncrement();
            System.err.println("match Object[]");
        }
    }
}
