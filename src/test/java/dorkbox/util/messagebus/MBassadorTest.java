package dorkbox.util.messagebus;

import dorkbox.util.messagebus.common.*;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.listeners.ExceptionThrowingListener;
import dorkbox.util.messagebus.listeners.IMessageListener;
import dorkbox.util.messagebus.listeners.Listeners;
import dorkbox.util.messagebus.listeners.MessagesListener;
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
        final MultiMBassador bus = createBus(listeners);


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
        assertEquals(InstancesPerListener * ConcurrentUnits, MessageTypes.Simple.getTimesHandled(MessagesListener.DefaultListener.class));
    }


    @Test
    public void testAsynchronousMessagePublication() throws Exception {

        ListenerFactory listeners = new ListenerFactory()
                .create(InstancesPerListener, Listeners.noHandlers());
        final MultiMBassador bus = createBus(listeners);


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
        };

        final MultiMBassador bus = new MultiMBassador();
        bus.addErrorHandler(ExceptionCounter);
        bus.start();
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
