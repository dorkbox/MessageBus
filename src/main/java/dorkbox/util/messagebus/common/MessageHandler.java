package dorkbox.util.messagebus.common;

import com.esotericsoftware.reflectasm.MethodAccess;
import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Synchronized;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Any method in any class annotated with the @Handler annotation represents a message handler. The class that contains
 * the handler is called a  message listener and more generally, any class containing a message handler in its class hierarchy
 * defines such a message listener.
 * <p>
 * <p>
 * Note: When sending messages to a handler that is of type ARRAY (either an object of type array, or a vararg), the JVM cannot
 *       tell the difference (the message that is being sent), if it is a vararg or array.
 *       <p>
 *       <p>
 *       BECAUSE OF THIS, we always treat the two the same
 *       <p>
 *       <p>
 *
 * @author bennidi
 *         Date: 11/14/12
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public class MessageHandler {

    // publish all listeners defined by the given class (includes
    // listeners defined in super classes)
    public static final MessageHandler[] get(final Class<?> target) {

        // publish all handlers (this will include all (inherited) methods directly annotated using @Handler)
        final Method[] allMethods = ReflectionUtils.getMethods(target);
        final int length = allMethods.length;

        final ArrayList<MessageHandler> finalMethods = new ArrayList<MessageHandler>(length);
        Method method;

        for (int i=0;i<length;i++) {
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

                Method overriddenHandler = ReflectionUtils.getOverridingMethod(method, target);
                if (overriddenHandler == null) {
                    overriddenHandler = method;
                }

                // if a handler is overwritten it inherits the configuration of its parent method
                finalMethods.add(new MessageHandler(overriddenHandler, handler));
            }
        }

        MessageHandler[] array = finalMethods.toArray(new MessageHandler[finalMethods.size()]);
        return array;
    }

    private final MethodAccess handler;
    private final int methodIndex;

    private final Class<?>[] handledMessages;
    private final boolean acceptsSubtypes;
    private final boolean acceptsVarArgs;

    private final boolean isSynchronized;

    public MessageHandler(Method handler, Handler handlerConfig) {
        super();

        if (handler == null) {
            throw new IllegalArgumentException("The message handler configuration may not be null");
        }

        Class<?>[] handledMessages = handler.getParameterTypes();

        this.handler = MethodAccess.get(handler.getDeclaringClass());
        this.methodIndex = this.handler.getIndex(handler.getName(), handledMessages);

        this.acceptsSubtypes = handlerConfig.acceptSubtypes();
        this.isSynchronized  = ReflectionUtils.getAnnotation(handler, Synchronized.class) != null;
        this.handledMessages = handledMessages;

        this.acceptsVarArgs = handledMessages.length == 1 && handledMessages[0].isArray() && handlerConfig.acceptVarargs();
    }

    public final boolean isSynchronized() {
        return this.isSynchronized;
    }

    public final MethodAccess getHandler() {
        return this.handler;
    }

    public final int getMethodIndex() {
        return this.methodIndex;
    }

    public final Class<?>[] getHandledMessages() {
        return this.handledMessages;
    }

    public final boolean acceptsSubtypes() {
        return this.acceptsSubtypes;
    }

    public final boolean acceptsVarArgs() {
        return this.acceptsVarArgs;
    }

    @Override
    public final int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.acceptsSubtypes ? 1231 : 1237);
        result = prime * result + Arrays.hashCode(this.handledMessages);
        result = prime * result + (this.handler == null ? 0 : this.handler.hashCode());
        result = prime * result + (this.isSynchronized ? 1231 : 1237);
        return result;
    }

    @Override
    public final boolean equals(Object obj) {
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
        } else if (!this.handler.equals(other.handler)) {
            return false;
        }
        if (this.isSynchronized != other.isSynchronized) {
            return false;
        }
        return true;
    }
}
