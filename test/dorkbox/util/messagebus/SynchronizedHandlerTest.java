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
package dorkbox.util.messagebus;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import dorkbox.messageBus.IMessageBus;
import dorkbox.messageBus.annotations.Handler;
import dorkbox.messageBus.annotations.Synchronized;
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
//            System.err.println(counter.publish());
        }
    }
}
