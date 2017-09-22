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
package dorkbox.util.messagebus.listeners;

import dorkbox.messageBus.annotations.Handler;
import dorkbox.util.messagebus.messages.ICountable;

/**
 *
 * @author bennidi
 *         Date: 5/24/13
 */
public class ICountableListener {

    private static abstract class BaseListener {
        @Handler
        public void handle(ICountable message){
            message.handled(this.getClass());
        }
    }

    public static class DefaultListener extends BaseListener {
        @Override
        public void handle(ICountable message){
            super.handle(message);
        }
    }

    public static class NoSubtypesListener extends BaseListener {
        @Override
        @Handler(acceptSubtypes = false)
        public void handle(ICountable message){
            super.handle(message);
        }
    }

    public static class DisabledListener extends BaseListener {
        @Override
        @Handler(enabled = false)
        public void handle(ICountable message){
            super.handle(message);
        }
    }
}
