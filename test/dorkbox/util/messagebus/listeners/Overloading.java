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
package dorkbox.util.messagebus.listeners;

import dorkbox.messageBus.annotations.Handler;
import dorkbox.util.messagebus.messages.AbstractMessage;

/**
 * Some handlers and message types to test correct functioning of overloaded message handlers
 */
public class Overloading {

    public static class TestMessageA extends AbstractMessage {}

    public static class TestMessageB extends AbstractMessage {}

    public static class ListenerSub extends ListenerBase {
        @Handler
        public void handleEvent(TestMessageB event) {
            event.handled(this.getClass());
        }
    }

    public static class ListenerBase {
        /**
         * (!) If this method is removed, NO event handler will be called.
         */
        @Handler
        public void handleEventWithNonOverloadedMethodName(TestMessageA event) {
            event.handled(this.getClass());
        }

        @Handler
        public void handleEvent(TestMessageA event) {
            event.handled(this.getClass());
        }
    }
}
