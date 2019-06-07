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

import org.junit.Test;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.annotations.Subscribe;
import dorkbox.util.messagebus.common.MessageBusTest;

/**
 * Very simple test to verify dispatch to correct message handler
 *
 * @author bennidi
 *         Date: 1/17/13
 */
public class MethodDispatchTest extends MessageBusTest{

   private boolean listener1Called = false;
   private boolean listener2Called = false;



    // a simple event listener
    public class EventListener1 {

        @Subscribe
        public void handleString(String s) {
             MethodDispatchTest.this.listener1Called = true;
        }

    }

    // the same handlers as its super class
    public class EventListener2 extends EventListener1 {

        // redefine handler implementation (not configuration)
        @Override
        public void handleString(String s) {
           MethodDispatchTest.this.listener2Called = true;
        }

    }

    @Test
    public void testDispatch1(){
        MessageBus bus = createBus();
        EventListener2 listener2 = new EventListener2();
        bus.subscribe(listener2);
        bus.publish("jfndf");
        assertTrue(this.listener2Called);
        assertFalse(this.listener1Called);

        EventListener1 listener1 = new EventListener1();
        bus.subscribe(listener1);
        bus.publish("jfndf");
        assertTrue(this.listener1Called);
    }
}
