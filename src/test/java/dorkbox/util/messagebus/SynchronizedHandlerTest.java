package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Synchronized;
import dorkbox.util.messagebus.common.MessageBusTest;

/**
 * Todo: Add javadoc
 *
 * @author bennidi
 *         Date: 3/31/13
 */
public class SynchronizedHandlerTest extends MessageBusTest {

    private static AtomicInteger counter = new AtomicInteger(0);
    private static int numberOfMessages = 1000;
    private static int numberOfListeners = 1000;

    @Test
    public void testSynchronizedWithSynchronousInvocation(){
        IMessageBus bus = createBus();
        for(int i = 0; i < numberOfListeners; i++){
            SynchronizedWithSynchronousDelivery handler = new SynchronizedWithSynchronousDelivery();
            bus.subscribe(handler);
        }

        for (int i = 0; i < numberOfMessages; i++) {
            bus.publishAsync(new Object());
        }

        int totalCount = numberOfListeners * numberOfMessages;

        // wait for last publication
        while (bus.hasPendingMessages()) {
            pause(100);
        }

        if (totalCount != counter.get()) {
            fail("Count '" + counter.get() + "' was incorrect!");
        }
    }

    public static class SynchronizedWithSynchronousDelivery {
        @Handler
        @Synchronized
        public void handleMessage(Object o){
            counter.getAndIncrement();
//            System.err.println(counter.get());
        }
    }
}
