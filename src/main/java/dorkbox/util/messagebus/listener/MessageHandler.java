package dorkbox.util.messagebus.listener;

import java.lang.reflect.Method;
import java.util.Arrays;

import com.esotericsoftware.reflectasm.MethodAccess;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Synchronized;
import dorkbox.util.messagebus.common.ReflectionUtils;

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

    private final MethodAccess handler;
    private final int methodIndex;

    private final Class<?>[] handledMessages;
    private final boolean acceptsSubtypes;
    private final boolean acceptsVarArgs;
    private final MessageListener listenerConfig;

    private final boolean isSynchronized;

    public MessageHandler(Method handler, Handler handlerConfig, MessageListener listenerMetadata) {
        super();

        if (handler == null) {
            throw new IllegalArgumentException("The message handler configuration may not be null");
        }

        Class<?>[] handledMessages = handler.getParameterTypes();

        this.handler = MethodAccess.get(handler.getDeclaringClass());
        this.methodIndex = this.handler.getIndex(handler.getName(), handledMessages);

        this.acceptsSubtypes = handlerConfig.acceptSubtypes();
        this.listenerConfig  = listenerMetadata;
        this.isSynchronized  = ReflectionUtils.getAnnotation(handler, Synchronized.class) != null;
        this.handledMessages = handledMessages;

        this.acceptsVarArgs = handledMessages.length == 1 && handledMessages[0].isArray() && handlerConfig.acceptVarargs();
    }

    public boolean isSynchronized(){
        return this.isSynchronized;
    }

    // only in unit test
    public boolean isFromListener(Class<?> listener){
        return this.listenerConfig.isFromListener(listener);
    }

    public MethodAccess getHandler() {
        return this.handler;
    }

    public int getMethodIndex() {
        return this.methodIndex;
    }

    public Class<?>[] getHandledMessages() {
        return this.handledMessages;
    }

    public boolean acceptsSubtypes() {
        return this.acceptsSubtypes;
    }

    public boolean acceptsVarArgs() {
        return this.acceptsVarArgs;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.acceptsSubtypes ? 1231 : 1237);
        result = prime * result + Arrays.hashCode(this.handledMessages);
        result = prime * result + (this.handler == null ? 0 : this.handler.hashCode());
        result = prime * result + (this.isSynchronized ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
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
