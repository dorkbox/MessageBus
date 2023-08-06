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
package dorkbox.messagebus.common;

import org.junit.Before;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.error.IPublicationErrorHandler;
import dorkbox.messageBus.error.PublicationError;
import dorkbox.messagebus.messages.MessageTypes;

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
            error.getCause().printStackTrace();
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
        MessageTypes.resetAll();
    }


    public
    MessageBus createBus() {
        MessageBus bus = new MessageBus();
        bus.addErrorHandler(TestFailingHandler);
        return bus;
    }

    public
    MessageBus createBus(ListenerFactory listeners) {
        MessageBus bus = new MessageBus();
        bus.addErrorHandler(TestFailingHandler);
        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(bus, listeners), ConcurrentUnits);
        return bus;
    }
}
