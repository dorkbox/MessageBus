package dorkbox.util.messagebus.listener;

import java.util.Collection;

import dorkbox.util.messagebus.common.StrongConcurrentSetV8;

/**
 * All instances of any class that defines at least one message handler (see @MessageHandler) are message listeners. Thus, a message
 * listener is any object capable of receiving messages by means of defined message handlers. There are no restrictions about the number of
 * allowed message handlers in a message listener.
 *
 * A message listener can be configured using the @Listener annotation but is always implicitly configured by the handler definition it
 * contains.
 *
 * This class is an internal representation of a message listener used to encapsulate all relevant objects and data about that message
 * listener, especially all its handlers. There will be only one instance of MessageListener per message listener class and message bus
 * instance.
 *
 * @author bennidi Date: 12/16/12
 */
public class MessageListener {

    private final Collection<MessageHandler> handlers;
    private Class<?> listenerDefinition;

    public MessageListener(Class<?> listenerDefinition, int size) {
        this.handlers = new StrongConcurrentSetV8<MessageHandler>(size, 0.8F, 1);
        this.listenerDefinition = listenerDefinition;
    }

    // only in unit test
    public boolean isFromListener(Class<?> listener) {
        return this.listenerDefinition.equals(listener);
    }

    public boolean addHandler(MessageHandler messageHandler) {
        return this.handlers.add(messageHandler);
    }

    public Collection<MessageHandler> getHandlers() {
        return this.handlers;
    }
}
