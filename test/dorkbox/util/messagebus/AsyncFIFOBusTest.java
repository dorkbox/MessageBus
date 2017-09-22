/*
 * Copyright 2014 Benjamin Diedrichsen
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

import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import dorkbox.messageBus.IMessageBus;
import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.annotations.Handler;
import dorkbox.util.messagebus.common.MessageBusTest;

/**
 * NOTE: the async publication is truly async. There is no guaranteed order to when it's handled.
 *
 * @author bennidi
 *         Date: 3/30/14
 */
public class AsyncFIFOBusTest extends MessageBusTest {

    @Test
    public void testSingleThreadedSyncFIFO(){
        // create a fifo bus with 1000 concurrently subscribed listeners
        IMessageBus fifoBUs = new MessageBus();

        List<Listener> listeners = new LinkedList<Listener>();
        for(int i = 0; i < 1000 ; i++){
            Listener listener = new Listener();
            listeners.add(listener);
            fifoBUs.subscribe(listener);
        }

        // prepare set of messages in increasing order
        int[] messages = new int[1000];
        for(int i = 0; i < messages.length ; i++){
             messages[i] = i;
        }
        // publish in ascending order
        for(Integer message : messages) {
            fifoBUs.publish(message);
        }

        while(fifoBUs.hasPendingMessages()) {
            pause(1000);
        }

        for(Listener listener : listeners){
            assertEquals(messages.length, listener.receivedSync.size());
            for(int i=0; i < messages.length; i++){
                assertEquals(messages[i], listener.receivedSync.get(i));
            }
        }
        fifoBUs.shutdown();
    }

    public static class Listener {

        private List<Integer> receivedSync = new LinkedList<Integer>();

        @Handler
        public void handleSync(Integer message){
            synchronized (this.receivedSync) {
                this.receivedSync.add(message);
            }
        }

    }
}
