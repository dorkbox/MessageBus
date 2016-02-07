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
package dorkbox.messagebus.common;

import dorkbox.messagebus.annotations.Handler;
import dorkbox.messagebus.annotations.References;
import dorkbox.messagebus.annotations.Synchronized;
import dorkbox.messagebus.utils.ReflectionUtils;
import dorkbox.messagebus.annotations.Listener;

import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * Any method in any class annotated with the @Handler annotation represents a message handler. The class that contains
 * the handler is called a  message listener and more generally, any class containing a message handler in its class hierarchy
 * defines such a message listener.
 * <p/>
 * <p/>
 * Note: When sending messages to a handler that is of type ARRAY (either an object of type array, or a vararg), the JVM cannot
 * tell the difference (the message that is being sent), if it is a vararg or array.
 * <p/>
 * <p/>
 * BECAUSE OF THIS, we always treat the two the same
 * <p/>
 *
 * @author bennidi
 *         Date: 11/14/12
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public
class MessageHandler {

    private static final MessageHandler[] EMPTY_MESSAGEHANDLERS = new MessageHandler[0];

    // publish all listeners defined by the given class (includes listeners defined in super classes)
    public static
    MessageHandler[] get(final Class<?> messageClass) {

        // publish all handlers (this will include all (inherited) methods directly annotated using @Handler)
        final Method[] allMethods = ReflectionUtils.getMethods(messageClass);
        final int length = allMethods.length;

        final ArrayList<MessageHandler> finalMethods = new ArrayList<MessageHandler>(length);
        Method method;

        for (int i = 0; i < length; i++) {
            method = allMethods[i];

            // retain only those that are at the bottom of their respective class hierarchy (deepest overriding method)
            if (!ReflectionUtils.containsOverridingMethod(allMethods, method)) {

                // for each handler there will be no overriding method that specifies @Handler annotation
                // but an overriding method does inherit the listener configuration of the overwritten method
                final Handler handler = ReflectionUtils.getAnnotation(method, Handler.class);
                if (handler == null || !handler.enabled()) {
                    // disabled or invalid listeners are ignored
                    continue;
                }

                Method overriddenHandler = ReflectionUtils.getOverridingMethod(method, messageClass);
                if (overriddenHandler == null) {
                    overriddenHandler = method;
                }

                // if a handler is overwritten it inherits the configuration of its parent method
                finalMethods.add(new MessageHandler(messageClass, overriddenHandler, handler));
            }
        }

        return finalMethods.toArray(EMPTY_MESSAGEHANDLERS);
    }

    private final Method method;


    private final Class<?>[] handledMessages;
    private final boolean acceptsSubtypes;

    private final boolean isSynchronized;
    private final boolean isWeakReference;

    private
    MessageHandler(final Class<?> clazz, final Method method, final Handler config) {
        if (method == null) {
            throw new IllegalArgumentException("The message method configuration may not be null");
        }

        this.method = method;
        this.acceptsSubtypes = config.acceptSubtypes();
        this.handledMessages = method.getParameterTypes();
        this.isSynchronized = ReflectionUtils.getAnnotation(method, Synchronized.class) != null;

        Listener annotation = ReflectionUtils.getAnnotation(clazz, Listener.class);
        this.isWeakReference = annotation != null && annotation.references().equals(References.Weak);
    }

    public final
    boolean isSynchronized() {
        return this.isSynchronized;
    }

    public final
    boolean isWeakReference() {
        // this is checked every time a new subscription is created.
        return isWeakReference;
    }

    public final
    Method getMethod() {
        return this.method;
    }

    public final
    Class<?>[] getHandledMessages() {
        return this.handledMessages;
    }

    public final
    boolean acceptsSubtypes() {
        return this.acceptsSubtypes;
    }

    @Override
    public final
    int hashCode() {
        return this.method.hashCode();
    }

    @Override
    public final
    boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }

        MessageHandler other = (MessageHandler) obj;
        return this.method.equals(other.method);
    }
}
