/*
 * Copyright 2012 Benjamin Diedrichsen
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

import org.junit.Test;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.error.IPublicationErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messagebus.common.ConcurrentExecutor;
import dorkbox.messagebus.common.ListenerFactory;
import dorkbox.messagebus.common.MessageBusTest;
import dorkbox.messagebus.common.MessageManager;
import dorkbox.messagebus.common.TestUtil;
import dorkbox.messagebus.listeners.ExceptionThrowingListener;
import dorkbox.messagebus.listeners.IMessageListener;
import dorkbox.messagebus.listeners.Listeners;
import dorkbox.messagebus.listeners.MessageTypesListener;
import dorkbox.messagebus.messages.MessageTypes;
import dorkbox.messagebus.messages.MultipartMessage;
import dorkbox.messagebus.messages.StandardMessage;

/**
 * Test synchronous and asynchronous dispatch in single and multi-threaded scenario.
 *
 * @author bennidi
 *         Date: 2/8/12
 */
public class MBassadorTest extends MessageBusTest {


    @Test
    public void testSyncPublicationSyncHandlers() throws Exception {

        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, Listeners.synchronous())
                .create(InstancesPerListener, Listeners.noHandlers());
        final MessageBus bus = createBus(listeners);


        Runnable publishAndCheck = new Runnable() {
            @Override
            public void run() {
                StandardMessage standardMessage = new StandardMessage();
                MultipartMessage multipartMessage = new MultipartMessage();

                bus.publish(standardMessage);
                bus.publish(multipartMessage);
                bus.publish(MessageTypes.Simple);

                assertEquals(InstancesPerListener, standardMessage.getTimesHandled(IMessageListener.DefaultListener.class));
                assertEquals(InstancesPerListener, multipartMessage.getTimesHandled(IMessageListener.DefaultListener.class));
            }
        };

        // test single-threaded
        ConcurrentExecutor.runConcurrent(publishAndCheck, 1);

        // test multi-threaded
        MessageTypes.resetAll();
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Simple.getTimesHandled(IMessageListener.DefaultListener.class));
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Simple.getTimesHandled(MessageTypesListener.DefaultListener.class));
    }


    @Test
    public void testAsynchronousMessagePublication() throws Exception {

        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, Listeners.noHandlers());
        final MessageBus bus = createBus(listeners);


        final MessageManager messageManager = new MessageManager();

        Runnable publishAndCheck = new Runnable() {
            @Override
            public void run() {
                bus.publishAsync(MessageTypes.Simple);
            }
        };

        ConcurrentExecutor.runConcurrent(publishAndCheck, 1);
        do {
            pause(10);
        } while (bus.hasPendingMessages());

        MessageTypes.resetAll();
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);

        do {
            pause(10);
        } while (bus.hasPendingMessages());
        messageManager.waitForMessages(600);
    }


    @Test
    public void testExceptionInHandlerInvocation(){
        final AtomicInteger exceptionCount = new AtomicInteger(0);
        IPublicationErrorHandler ExceptionCounter = new IPublicationErrorHandler() {
            @Override
            public void handleError(PublicationError error) {
                exceptionCount.incrementAndGet();
            }

            @Override
            public void handleError(final String error, final Class<?> listenerClass) {
                // Printout the error itself
                System.out.println(new StringBuilder().append(error).append(": ").append(listenerClass.getSimpleName()).toString());
            }
        };

        final MessageBus bus = new MessageBus();
        bus.addErrorHandler(ExceptionCounter);

        ListenerFactory listeners = new ListenerFactory().create(InstancesPerListener, ExceptionThrowingListener.class);

        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);

        Runnable publishAndCheck = new Runnable() {
            @Override
            public void run() {
                bus.publishAsync(new StandardMessage());
            }
        };

        // multi threaded, 1 other thread
        ConcurrentExecutor.runConcurrent(publishAndCheck, 1);

        // we want to wait a reasonable amount of time to check.
        int count = 1000;
        while (count != 0 && InstancesPerListener != exceptionCount.get()) {
            count--;
            pause(10);
        }

        // NOTE: this looks like a good idea, but it's not.
        // while (bus.hasPendingMessages()) {
        //     pause(10);
        // }

        assertEquals(InstancesPerListener, exceptionCount.get());


        // multi threaded, `ConcurrentUnits` other threads
        int expected = InstancesPerListener * ConcurrentUnits;
        exceptionCount.set(0);

        // we want to wait a reasonable amount of time to check.
        count = 1000;
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        while (count != 0 && expected != exceptionCount.get()) {
            count--;
            pause(10);
        }

        // NOTE: this looks like a good idea, but it's not.
        // while (bus.hasPendingMessages()) {
        //     pause(10);
        // }

        assertEquals(expected, exceptionCount.get());
        bus.shutdown();
    }
}
