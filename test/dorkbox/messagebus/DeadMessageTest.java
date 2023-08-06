/*
 * Copyright 2013 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dorkbox.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.annotations.Subscribe;
import dorkbox.messageBus.error.DeadMessage;
import dorkbox.messagebus.common.ConcurrentExecutor;
import dorkbox.messagebus.common.ListenerFactory;
import dorkbox.messagebus.common.MessageBusTest;
import dorkbox.messagebus.common.TestUtil;
import dorkbox.messagebus.listeners.IMessageListener;
import dorkbox.messagebus.listeners.MessageTypesListener;
import dorkbox.messagebus.listeners.ObjectListener;

/**
 * Verify correct behaviour in case of message publications that do not have any matching subscriptions
 *
 * @author bennidi
 *         Date: 1/18/13
 */
public class DeadMessageTest extends MessageBusTest {

    private static final AtomicInteger deadMessages = new AtomicInteger(0);

    @Override
    @Before
    public void beforeTest(){
        deadMessages.set(0);
    }


    @Test
    public void testDeadMessage(){
        final MessageBus bus = createBus();
        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, IMessageListener.DefaultListener.class)
                .create(InstancesPerListener, IMessageListener.DisabledListener.class)
                .create(InstancesPerListener, MessageTypesListener.DefaultListener.class)
                .create(InstancesPerListener, MessageTypesListener.DisabledListener.class)
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
        final MessageBus bus = createBus();
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
        @Subscribe
        public void handle(DeadMessage message){
            deadMessages.incrementAndGet();
        }
    }
}
