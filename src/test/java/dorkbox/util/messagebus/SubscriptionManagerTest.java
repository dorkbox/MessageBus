package dorkbox.util.messagebus;

import org.junit.Test;

import dorkbox.util.messagebus.common.AssertSupport;
import dorkbox.util.messagebus.common.ConcurrentExecutor;
import dorkbox.util.messagebus.common.ListenerFactory;
import dorkbox.util.messagebus.common.SubscriptionValidator;
import dorkbox.util.messagebus.common.TestUtil;
import dorkbox.util.messagebus.listeners.AbstractMessageListener;
import dorkbox.util.messagebus.listeners.ICountableListener;
import dorkbox.util.messagebus.listeners.IMessageListener;
import dorkbox.util.messagebus.listeners.IMultipartMessageListener;
import dorkbox.util.messagebus.listeners.MessagesListener;
import dorkbox.util.messagebus.listeners.MultipartMessageListener;
import dorkbox.util.messagebus.listeners.Overloading;
import dorkbox.util.messagebus.listeners.StandardMessageListener;
import dorkbox.util.messagebus.messages.AbstractMessage;
import dorkbox.util.messagebus.messages.ICountable;
import dorkbox.util.messagebus.messages.IMessage;
import dorkbox.util.messagebus.messages.IMultipartMessage;
import dorkbox.util.messagebus.messages.MessageTypes;
import dorkbox.util.messagebus.messages.MultipartMessage;
import dorkbox.util.messagebus.messages.StandardMessage;

/**
 *
 * Test the subscriptions as generated and organized by the subscription manager. Tests use different sets of listeners
 * and corresponding expected set of subscriptions that should result from subscribing the listeners. The subscriptions
 * are tested for the type of messages they should handle and
 *
 * @author bennidi
 *         Date: 5/12/13
 */
public class SubscriptionManagerTest extends AssertSupport {

    private static final int InstancesPerListener = 5000;

    @Test
    public void testIMessageListener(){
        ListenerFactory listeners = listeners(
                IMessageListener.DefaultListener.class,
                IMessageListener.DisabledListener.class,
                IMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(IMessageListener.DefaultListener.class).handles(IMessage.class,
                        AbstractMessage.class, IMultipartMessage.class, StandardMessage.class, MessageTypes.class)
                .listener(IMessageListener.NoSubtypesListener.class).handles(IMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testAbstractMessageListener(){
        ListenerFactory listeners = listeners(
                AbstractMessageListener.DefaultListener.class,
                AbstractMessageListener.DisabledListener.class,
                AbstractMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(AbstractMessageListener.NoSubtypesListener.class).handles(AbstractMessage.class)
                .listener(AbstractMessageListener.DefaultListener.class).handles(StandardMessage.class, AbstractMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testMessagesListener(){
        ListenerFactory listeners = listeners(
                MessagesListener.DefaultListener.class,
                MessagesListener.DisabledListener.class,
                MessagesListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(MessagesListener.NoSubtypesListener.class).handles(MessageTypes.class)
                .listener(MessagesListener.DefaultListener.class).handles(MessageTypes.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testMultipartMessageListener(){
        ListenerFactory listeners = listeners(
                MultipartMessageListener.DefaultListener.class,
                MultipartMessageListener.DisabledListener.class,
                MultipartMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(MultipartMessageListener.NoSubtypesListener.class).handles(MultipartMessage.class)
                .listener(MultipartMessageListener.DefaultListener.class).handles(MultipartMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testIMultipartMessageListener(){
        ListenerFactory listeners = listeners(
                IMultipartMessageListener.DefaultListener.class,
                IMultipartMessageListener.DisabledListener.class,
                IMultipartMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(IMultipartMessageListener.NoSubtypesListener.class).handles(IMultipartMessage.class)
                .listener(IMultipartMessageListener.DefaultListener.class).handles(MultipartMessage.class, IMultipartMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testStandardMessageListener(){
        ListenerFactory listeners = listeners(
                StandardMessageListener.DefaultListener.class,
                StandardMessageListener.DisabledListener.class,
                StandardMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(StandardMessageListener.NoSubtypesListener.class).handles(StandardMessage.class)
                .listener(StandardMessageListener.DefaultListener.class).handles(StandardMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testICountableListener(){
        ListenerFactory listeners = listeners(
                ICountableListener.DefaultListener.class,
                ICountableListener.DisabledListener.class,
                ICountableListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(ICountableListener.DefaultListener.class).handles(ICountable.class)
                .listener(ICountableListener.DefaultListener.class).handles(MultipartMessage.class, IMultipartMessage.class, ICountable.class, StandardMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public void testMultipleMessageListeners(){
        ListenerFactory listeners = listeners(
                ICountableListener.DefaultListener.class,
                ICountableListener.DisabledListener.class,
                IMultipartMessageListener.DefaultListener.class,
                IMultipartMessageListener.DisabledListener.class,
                MessagesListener.DefaultListener.class,
                MessagesListener.DisabledListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(ICountableListener.DefaultListener.class).handles(MultipartMessage.class, IMultipartMessage.class, ICountable.class, StandardMessage.class)
                .listener(IMultipartMessageListener.DefaultListener.class).handles(MultipartMessage.class, IMultipartMessage.class)
                .listener(MessagesListener.DefaultListener.class).handles(MessageTypes.class);

        runTestWith(listeners, expectedSubscriptions);
    }


    @Test
    public void testOverloadedMessageHandlers(){
        ListenerFactory listeners = listeners(
                Overloading.ListenerBase.class,
                Overloading.ListenerSub.class);

        SubscriptionManager subscriptionManager = new SubscriptionManager(1);
        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(subscriptionManager, listeners), 1);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners)
                .listener(Overloading.ListenerBase.class).handles(Overloading.TestMessageA.class, Overloading.TestMessageA.class)
                .listener(Overloading.ListenerSub.class).handles(Overloading.TestMessageA.class, Overloading.TestMessageA.class, Overloading.TestMessageB.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    private ListenerFactory listeners(Class<?> ...listeners){
        ListenerFactory factory = new ListenerFactory();
        for (Class<?> listener : listeners){
            factory.create(InstancesPerListener, listener);
        }
        return factory;
    }

    private void runTestWith(final ListenerFactory listeners, final SubscriptionValidator validator){
        final SubscriptionManager subscriptionManager = new SubscriptionManager(1);

        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(subscriptionManager, listeners), 1);

        validator.validate(subscriptionManager);

        ConcurrentExecutor.runConcurrent(TestUtil.unsubscriber(subscriptionManager, listeners), 1);

        listeners.clear();

        validator.validate(subscriptionManager);
    }
}

