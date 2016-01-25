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
package dorkbox.util.messagebus.common;

import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Synchronized;
import dorkbox.util.messagebus.utils.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

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
                finalMethods.add(new MessageHandler(overriddenHandler, handler));
            }
        }

        return finalMethods.toArray(new MessageHandler[0]);
    }

    private final MethodAccess handler;
    private final int methodIndex;

    private final Class<?>[] handledMessages;
    private final boolean acceptsSubtypes;
    private final Class<?> varArgClass;

    private final boolean isSynchronized;

    public
    MessageHandler(Method handler, Handler handlerConfig) {
        super();

        if (handler == null) {
            throw new IllegalArgumentException("The message handler configuration may not be null");
        }

        Class<?>[] handledMessages = handler.getParameterTypes();

        this.handler = MethodAccess.get(handler.getDeclaringClass());
        this.methodIndex = this.handler.getIndex(handler.getName(), handledMessages);

        this.acceptsSubtypes = handlerConfig.acceptSubtypes();
        this.isSynchronized = ReflectionUtils.getAnnotation(handler, Synchronized.class) != null;
        this.handledMessages = handledMessages;

        if (handledMessages.length == 1 && handledMessages[0].isArray() && handlerConfig.acceptVarargs()) {
            this.varArgClass = handledMessages[0].getComponentType();
        }
        else {
            this.varArgClass = null;
        }
    }

    public final
    boolean isSynchronized() {
        return this.isSynchronized;
    }

    public final
    MethodAccess getHandler() {
        return this.handler;
    }

    public final
    int getMethodIndex() {
        return this.methodIndex;
    }

    public final
    Class<?>[] getHandledMessages() {
        return this.handledMessages;
    }

    public final
    Class<?> getVarArgClass() {
        return this.varArgClass;
    }

    public final
    boolean acceptsSubtypes() {
        return this.acceptsSubtypes;
    }

    public final
    boolean acceptsVarArgs() {
        return this.varArgClass != null;
    }

    @Override
    public final
    int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.acceptsSubtypes ? 1231 : 1237);
        result = prime * result + Arrays.hashCode(this.handledMessages);
        result = prime * result + (this.handler == null ? 0 : this.handler.hashCode());
        result = prime * result + (this.isSynchronized ? 1231 : 1237);
        return result;
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
        if (this.acceptsSubtypes != other.acceptsSubtypes) {
            return false;
        }
        if (!Arrays.equals(this.handledMessages, other.handledMessages)) {
            return false;
        }
        if (this.handler == null) {
            if (other.handler != null) {
                return false;
            }
        }
        else if (!this.handler.equals(other.handler)) {
            return false;
        }
        if (this.isSynchronized != other.isSynchronized) {
            return false;
        }
        return true;
    }
}
