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

import org.junit.Assert;
import org.junit.Test;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.error.IPublicationErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messagebus.common.ConcurrentExecutor;
import dorkbox.messagebus.common.ListenerFactory;
import dorkbox.messagebus.common.MessageBusTest;
import dorkbox.messagebus.common.TestUtil;
import dorkbox.messagebus.listeners.ExceptionThrowingListener;
import dorkbox.messagebus.listeners.IMessageListener;
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
public class SyncBusTest extends MessageBusTest {

    @Test
    public void testSynchronousMessagePublication() throws Exception {

        final MessageBus bus = new MessageBus();
        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, IMessageListener.DefaultListener.class)
                .create(InstancesPerListener, IMessageListener.DisabledListener.class)
                .create(InstancesPerListener, MessageTypesListener.DefaultListener.class)
                .create(InstancesPerListener, MessageTypesListener.DisabledListener.class)
                .create(InstancesPerListener, Object.class);


        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);

        Runnable publishAndCheck = new Runnable() {
            @Override
            public void run() {
                StandardMessage standardMessage = new StandardMessage();
                MultipartMessage multipartMessage = new MultipartMessage();

                bus.publish(standardMessage);
                bus.publish(multipartMessage);
                bus.publish(MessageTypes.Simple);
                bus.publish(MessageTypes.Multipart);

                assertEquals(InstancesPerListener, standardMessage.getTimesHandled(IMessageListener.DefaultListener.class));
                assertEquals(InstancesPerListener, multipartMessage.getTimesHandled(IMessageListener.DefaultListener.class));
            }
        };

        // single threaded
        ConcurrentExecutor.runConcurrent(publishAndCheck, 1);

        // multi threaded
        MessageTypes.resetAll();
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Simple.getTimesHandled(IMessageListener.DefaultListener.class));
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Multipart.getTimesHandled(IMessageListener.DefaultListener.class));
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Simple.getTimesHandled(MessageTypesListener.DefaultListener.class));
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Multipart.getTimesHandled(MessageTypesListener.DefaultListener.class));

        bus.shutdown();
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

        Runnable publish = new Runnable() {
            @Override
            public void run() {
                bus.publish(new StandardMessage());
            }
        };

        // single threaded
        ConcurrentExecutor.runConcurrent(publish, 1);

        exceptionCount.set(0);

        // multi threaded
        ConcurrentExecutor.runConcurrent(publish, ConcurrentUnits);
        assertEquals(InstancesPerListener * ConcurrentUnits, exceptionCount.get());

        bus.shutdown();
    }


    static class IncrementingMessage{

        private int count = 1;

        public void markHandled(int newVal){
            // only transitions by the next handler are allowed
            if(this.count == newVal || this.count + 1 == newVal) {
                this.count = newVal;
            } else {
                Assert.fail("Message was handled out of order");
            }
        }
    }

}
