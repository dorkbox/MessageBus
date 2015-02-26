package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import dorkbox.util.messagebus.MultiMBassador;
import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.common.ConcurrentExecutor;
import dorkbox.util.messagebus.common.DeadMessage;
import dorkbox.util.messagebus.common.ListenerFactory;
import dorkbox.util.messagebus.common.MessageBusTest;
import dorkbox.util.messagebus.common.TestUtil;
import dorkbox.util.messagebus.listeners.IMessageListener;
import dorkbox.util.messagebus.listeners.MessagesListener;
import dorkbox.util.messagebus.listeners.ObjectListener;

/**
 * Verify correct behaviour in case of message publications that do not have any matching subscriptions
 *
 * @author bennidi
 *         Date: 1/18/13
 */
public class DeadMessageTest extends MessageBusTest{

    private static final AtomicInteger deadMessages = new AtomicInteger(0);

    @Override
    @Before
    public void beforeTest(){
        deadMessages.set(0);
    }


    @Test
    public void testDeadMessage(){
        final MultiMBassador bus = createBus();
        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, IMessageListener.DefaultListener.class)
                .create(InstancesPerListener, IMessageListener.DisabledListener.class)
                .create(InstancesPerListener, MessagesListener.DefaultListener.class)
                .create(InstancesPerListener, MessagesListener.DisabledListener.class)
                .create(InstancesPerListener, DeadMessagHandler.class)
                .create(InstancesPerListener, Object.class);


        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);

        Runnable publishUnhandledMessage = new Runnable() {
            @Override
            public void run() {
                for(int i=0; i < IterationsPerThread; i++){
                    int variation = i % 3;
                    switch (variation){
                        case 0:bus.publish(new Object());break;
                        case 1:bus.publish(i);break;
                        case 2:bus.publish(String.valueOf(i));break;
                    }
                }

            }
        };

        ConcurrentExecutor.runConcurrent(publishUnhandledMessage, ConcurrentUnits);

        assertEquals(InstancesPerListener * IterationsPerThread * ConcurrentUnits, deadMessages.get());
    }



    @Test
    public void testUnsubscribingAllListeners() {
        final MultiMBassador bus = createBus();
        ListenerFactory deadMessageListener = new ListenerFactory()
                .create(InstancesPerListener, DeadMessagHandler.class);

        ListenerFactory objectListener = new ListenerFactory()
                .create(InstancesPerListener, ObjectListener.class);

        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, deadMessageListener), ConcurrentUnits);

        // Only dead message handlers available
        bus.publish(new Object());

        // The message should be caught as dead message since there are no subscribed listeners
        assertEquals(InstancesPerListener, deadMessages.get());

        // Clear deadmessage for future tests
        deadMessages.set(0);

        // Add object listeners and publish again
        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, objectListener), ConcurrentUnits);
        bus.publish(new Object());

        // verify that no dead message events were produced
        assertEquals(0, deadMessages.get());

        // Unsubscribe all object listeners
        ConcurrentExecutor.runConcurrent(TestUtil.unsubscriber(bus, objectListener), ConcurrentUnits);

        // Only dead message handlers available
        bus.publish(new Object());

        // The message should be caught, as it's the only listener
        assertEquals(InstancesPerListener, deadMessages.get());
    }

    public static class DeadMessagHandler {
        @SuppressWarnings("unused")
        @Handler
        public void handle(DeadMessage message){
            deadMessages.incrementAndGet();
        }
    }
}
