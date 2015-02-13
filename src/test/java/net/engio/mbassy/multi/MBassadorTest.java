package net.engio.mbassy.multi;

import java.util.concurrent.atomic.AtomicInteger;

import net.engio.mbassy.multi.common.ConcurrentExecutor;
import net.engio.mbassy.multi.common.ListenerFactory;
import net.engio.mbassy.multi.common.MessageBusTest;
import net.engio.mbassy.multi.common.MessageManager;
import net.engio.mbassy.multi.common.TestUtil;
import net.engio.mbassy.multi.error.IPublicationErrorHandler;
import net.engio.mbassy.multi.error.PublicationError;
import net.engio.mbassy.multi.listeners.ExceptionThrowingListener;
import net.engio.mbassy.multi.listeners.IMessageListener;
import net.engio.mbassy.multi.listeners.Listeners;
import net.engio.mbassy.multi.listeners.MessagesListener;
import net.engio.mbassy.multi.messages.MessageTypes;
import net.engio.mbassy.multi.messages.MultipartMessage;
import net.engio.mbassy.multi.messages.StandardMessage;

import org.junit.Test;

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
        messageManager.waitForMessages(processingTimeInMS);

        MessageTypes.resetAll();
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        messageManager.waitForMessages(processingTimeInMS);

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
        pause(processingTimeInMS);
        assertEquals(InstancesPerListener, exceptionCount.get());


        // multi threaded
        exceptionCount.set(0);
        ConcurrentExecutor.runConcurrent(publishAndCheck, ConcurrentUnits);
        pause(processingTimeInMS);
        assertEquals(InstancesPerListener * ConcurrentUnits, exceptionCount.get());

        bus.shutdown();
    }




}
