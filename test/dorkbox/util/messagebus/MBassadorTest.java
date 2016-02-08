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
package dorkbox.util.messagebus;

import dorkbox.messagebus.MessageBus;
import dorkbox.util.messagebus.common.*;
import dorkbox.messagebus.error.IPublicationErrorHandler;
import dorkbox.messagebus.error.PublicationError;
import dorkbox.util.messagebus.listeners.ExceptionThrowingListener;
import dorkbox.util.messagebus.listeners.IMessageListener;
import dorkbox.util.messagebus.listeners.Listeners;
import dorkbox.util.messagebus.listeners.MessageTypesListener;
import dorkbox.util.messagebus.messages.MessageTypes;
import dorkbox.util.messagebus.messages.MultipartMessage;
import dorkbox.util.messagebus.messages.StandardMessage;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

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
        while (bus.hasPendingMessages()) {
            pause(10);
        }

        MessageTypes.resetAll();
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);

        while (bus.hasPendingMessages()) {
            pause(10);
        }
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
        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, ExceptionThrowingListener.class);
        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);

        Runnable publishAndCheck = new Runnable() {
            @Override
            public void run() {
                bus.publishAsync(new StandardMessage());

            }
        };

        // single threaded
        ConcurrentExecutor.runConcurrent(publishAndCheck, 1);
        while (bus.hasPendingMessages()) {
            pause(10);
        }
        assertEquals(InstancesPerListener, exceptionCount.get());


        // multi threaded
        exceptionCount.set(0);
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        while (bus.hasPendingMessages()) {
            pause(10);
        }

        assertEquals(InstancesPerListener * ConcurrentUnits, exceptionCount.get());
        bus.shutdown();
    }




}
