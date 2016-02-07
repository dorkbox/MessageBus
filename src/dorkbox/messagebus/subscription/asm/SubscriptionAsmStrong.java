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
 *
 *
 * Copyright 2015 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messagebus.subscription.asm;

import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.messagebus.common.MessageHandler;
import dorkbox.messagebus.error.ErrorHandler;
import dorkbox.messagebus.error.PublicationError;
import dorkbox.messagebus.subscription.Entry;
import dorkbox.messagebus.subscription.Subscription;

import java.lang.reflect.Method;

/**
 * A subscription is a container that manages exactly one message handler of all registered
 * message listeners of the same class, i.e. all subscribed instances (excluding subclasses) of a message
 * will be referenced in the subscription created for a message.
 * <p/>
 * There will be as many unique subscription objects per message listener class as there are message handlers
 * defined in the message listeners class hierarchy.
 * <p/>
 * This class uses the "single writer principle", so that the subscription are only MODIFIED by a single thread,
 * but are READ by X number of threads (in a safe way). This uses object thread visibility/publication to work.
 *
 * @author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
@SuppressWarnings("Duplicates")
final
class SubscriptionAsmStrong extends Subscription<Object> {
    private final AsmInvocation invocation;

    private final MethodAccess handlerAccess;
    private final int methodIndex;

    public
    SubscriptionAsmStrong(final Class<?> listenerClass, final MessageHandler handler) {
        // we use ASM here
        super(listenerClass, handler);

        AsmInvocation invocation = new AsmReflectiveInvocation();
        if (handler.isSynchronized()) {
            invocation = new AsmSynchronizedInvocation(invocation);
        }

        this.invocation = invocation;

        // we use ASM here
        Method method = handler.getMethod();
        this.handlerAccess = MethodAccess.get(method.getDeclaringClass());
        this.methodIndex = this.handlerAccess.getIndex(method.getName(), handler.getHandledMessages());
    }

    @Override
    public
    Entry<Object> createEntry(final Object listener, final Entry<Object> head) {
        return new Entry<Object>(listener, head);
    }

    @Override
    public
    boolean publish(final ErrorHandler errorHandler, final Object message) {
        final MethodAccess handler = this.handlerAccess;
        final int handleIndex = this.methodIndex;
        final AsmInvocation invocation = this.invocation;

        Entry head = headREF.get(this);
        Entry current = head;
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            try {
                invocation.invoke(listener, handler, handleIndex, message);
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                          .setCause(e)
                                                                          .setPublishedObject(message));
            }
        }

        return head != null;  // true if we have something to publish to, otherwise false
    }

    @Override
    public
    boolean publish(final ErrorHandler errorHandler,final Object message1, final Object message2) {
        final MethodAccess handler = this.handlerAccess;
        final int handleIndex = this.methodIndex;
        final AsmInvocation invocation = this.invocation;

        Entry head = headREF.get(this);
        Entry current = head;
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            try {
                invocation.invoke(listener, handler, handleIndex, message1, message2);
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                          .setCause(e)
                                                                          .setPublishedObject(message1, message2));
            }
        }

        return head != null;  // true if we have something to publish to, otherwise false
    }

    @Override
    public
    boolean publish(final ErrorHandler errorHandler,final Object message1, final Object message2, final Object message3) {
        final MethodAccess handler = this.handlerAccess;
        final int handleIndex = this.methodIndex;
        final AsmInvocation invocation = this.invocation;

        Entry head = headREF.get(this);
        Entry current = head;
        Object listener;
        while (current != null) {
            listener = current.getValue();
            current = current.next();

            try {
                invocation.invoke(listener, handler, handleIndex, message1, message2, message3);
            } catch (Throwable e) {
                errorHandler.handlePublicationError(new PublicationError().setMessage("Error during publication of message.")
                                                                          .setCause(e)
                                                                          .setPublishedObject(message1, message2, message3));
            }
        }

        return head != null;  // true if we have something to publish to, otherwise false
    }
}
