package dorkbox.util.messagebus;


/**
 * This interface defines the very basic message publication semantics according to the publish subscribe pattern.
 * Listeners can be subscribed and unsubscribed using the corresponding methods. When a listener is subscribed its
 * handlers will be registered and start to receive matching message publications.
 *
 * @Author bennidi
 * @author dorkbox, llc
 *         Date: 2/2/15
 */
public interface PubSubSupport {

    /**
     * Subscribe all handlers of the given listener. Any listener is only subscribed once
     * subsequent subscriptions of an already subscribed listener will be silently ignored
     */
    void subscribe(Object listener);

    /**
     * Immediately remove all registered message handlers (if any) of the given listener.
     * When this call returns all handlers have effectively been removed and will not
     * receive any messages (provided that running publications (iterators) in other threads
     * have not yet obtained a reference to the listener)
     * <p>
     * A call to this method passing any object that is not subscribed will not have any effect and is silently ignored.
     */
    void unsubscribe(Object listener);


    /**
     * Synchronously publish a message to all registered listeners. This includes listeners
     * defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered
     * listeners have been notified (invoked) of the message.
     */
    void publish(Object message);

    /**
     * Synchronously publish <b>TWO</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    void publish(Object message1, Object message2);

    /**
     * Synchronously publish <b>THREE</b> messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    void publish(Object message1, Object message2, Object message3);

    /**
     * Synchronously publish <b>AN ARRAY</b> of messages to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     */
    void publish(Object[] message);

    /**
     * Publish the message asynchronously to all registered listeners (that match the signature). This includes
     * listeners defined for super types of the given message type, provided they are not configured to reject
     * valid subtypes. The call returns when all matching handlers of all registered listeners have been notified
     * (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message);

    /**
     * Publish <b>TWO</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured
     * to reject valid subtypes. The call returns when all matching handlers of all registered listeners have
     * been notified (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message1, Object message2);

    /**
     * Publish <b>THREE</b> messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured to
     * reject valid subtypes. The call returns when all matching handlers of all registered listeners have been
     * notified (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object message1, Object message2, Object message3);

    /**
     * Publish <b>AN ARRAY</b> of messages asynchronously to all registered listeners (that match the signature). This
     * includes listeners defined for super types of the given message type, provided they are not configured to
     * reject valid subtypes. The call returns when all matching handlers of all registered listeners have been
     * notified (invoked) of the message.
     * <p>
     * <p>
     * The behavior of this method depends on availability of workers. If all workers are busy, then this method
     * will block until there is an available worker. If workers are available, then this method will immediately
     * return.
     */
    void publishAsync(Object[] messages);
}
