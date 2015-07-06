package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.MessageBus;
import dorkbox.util.messagebus.error.IPublicationErrorHandler;
import dorkbox.util.messagebus.error.PublicationError;
import dorkbox.util.messagebus.messages.MessageTypes;
import org.junit.Before;

/**
 * A base test that provides a factory for message bus that makes tests fail if any
 * publication error occurs
 *
 * @author bennidi
 *         Date: 3/2/13
 */
public abstract
class MessageBusTest extends AssertSupport {

    // this value probably needs to be adjusted depending on the performance of the underlying plattform
    // otherwise the tests will fail since asynchronous processing might not have finished when
    // evaluation is run
    protected static final int InstancesPerListener = 5000;
    protected static final int ConcurrentUnits = 10;
    protected static final int IterationsPerThread = 100;

    protected static final IPublicationErrorHandler TestFailingHandler = new IPublicationErrorHandler() {
        @Override
        public
        void handleError(PublicationError error) {
            error.getCause()
                 .printStackTrace();
            fail();
        }

        @Override
        public
        void handleError(final String error, final Class<?> listenerClass) {
            System.err.println(error + " " + listenerClass);
        }
    };

    @Before
    public
    void setUp() {
        for (MessageTypes mes : MessageTypes.values()) {
            mes.reset();
        }
    }


    public
    MessageBus createBus() {
        MessageBus bus = new MessageBus();
        bus.getErrorHandler()
           .addErrorHandler(TestFailingHandler);
        bus.start();
        return bus;
    }

    public
    MessageBus createBus(ListenerFactory listeners) {
        MessageBus bus = new MessageBus();
        bus.getErrorHandler()
           .addErrorHandler(TestFailingHandler);
        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);
        bus.start();
        return bus;
    }
}
