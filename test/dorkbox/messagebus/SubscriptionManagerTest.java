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
package dorkbox.messagebus;

import org.junit.Test;

import dorkbox.messageBus.SubscriptionMode;
import dorkbox.messageBus.subscription.SubscriptionManager;
import dorkbox.messagebus.messages.IMessage;
import dorkbox.messagebus.common.AssertSupport;
import dorkbox.messagebus.common.ConcurrentExecutor;
import dorkbox.messagebus.common.ListenerFactory;
import dorkbox.messagebus.common.SubscriptionValidator;
import dorkbox.messagebus.common.TestUtil;
import dorkbox.messagebus.listeners.AbstractMessageListener;
import dorkbox.messagebus.listeners.ICountableListener;
import dorkbox.messagebus.listeners.IMessageListener;
import dorkbox.messagebus.listeners.IMultipartMessageListener;
import dorkbox.messagebus.listeners.MessageTypesListener;
import dorkbox.messagebus.listeners.MultipartMessageListener;
import dorkbox.messagebus.listeners.Overloading;
import dorkbox.messagebus.listeners.StandardMessageListener;
import dorkbox.messagebus.messages.AbstractMessage;
import dorkbox.messagebus.messages.ICountable;
import dorkbox.messagebus.messages.IMultipartMessage;
import dorkbox.messagebus.messages.MessageTypes;
import dorkbox.messagebus.messages.MultipartMessage;
import dorkbox.messagebus.messages.StandardMessage;

/**
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
    public
    void testIMessageListener() {
        ListenerFactory listeners = listeners(IMessageListener.DefaultListener.class,
                                              IMessageListener.DisabledListener.class,
                                              IMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(IMessageListener.DefaultListener.class)
                             .handles(IMessage.class,
                                      AbstractMessage.class,
                                      IMultipartMessage.class,
                                      StandardMessage.class,
                                      MessageTypes.class);

        expectedSubscriptions.listener(IMessageListener.NoSubtypesListener.class)
                             .handles(IMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testAbstractMessageListener() {
        ListenerFactory listeners = listeners(AbstractMessageListener.DefaultListener.class,
                                              AbstractMessageListener.DisabledListener.class,
                                              AbstractMessageListener.NoSubtypesListener.class);

        final SubscriptionValidator subscriptionValidator = new SubscriptionValidator(listeners);

        subscriptionValidator.listener(AbstractMessageListener.NoSubtypesListener.class)
                             .handles(AbstractMessage.class);

        subscriptionValidator.listener(AbstractMessageListener.DefaultListener.class)
                             .handles(StandardMessage.class, AbstractMessage.class);

        runTestWith(listeners, subscriptionValidator);
    }

    @Test
    public
    void testMessagesListener() {
        ListenerFactory listeners = listeners(MessageTypesListener.DefaultListener.class,
                                              MessageTypesListener.DisabledListener.class,
                                              MessageTypesListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(MessageTypesListener.NoSubtypesListener.class)
                             .handles(MessageTypes.class);

        expectedSubscriptions.listener(MessageTypesListener.DefaultListener.class)
                             .handles(MessageTypes.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testMultipartMessageListener() {
        ListenerFactory listeners = listeners(MultipartMessageListener.DefaultListener.class,
                                              MultipartMessageListener.DisabledListener.class,
                                              MultipartMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(MultipartMessageListener.NoSubtypesListener.class)
                             .handles(MultipartMessage.class);

        expectedSubscriptions.listener(MultipartMessageListener.DefaultListener.class)
                             .handles(MultipartMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testIMultipartMessageListener() {
        ListenerFactory listeners = listeners(IMultipartMessageListener.DefaultListener.class,
                                              IMultipartMessageListener.DisabledListener.class,
                                              IMultipartMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(IMultipartMessageListener.NoSubtypesListener.class)
                             .handles(IMultipartMessage.class);

        expectedSubscriptions.listener(IMultipartMessageListener.DefaultListener.class)
                             .handles(MultipartMessage.class,
                                      IMultipartMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testStandardMessageListener() {
        ListenerFactory listeners = listeners(StandardMessageListener.DefaultListener.class,
                                              StandardMessageListener.DisabledListener.class,
                                              StandardMessageListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(StandardMessageListener.NoSubtypesListener.class)
                             .handles(StandardMessage.class);

        expectedSubscriptions.listener(StandardMessageListener.DefaultListener.class)
                             .handles(StandardMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testICountableListener() {
        ListenerFactory listeners = listeners(ICountableListener.DefaultListener.class,
                                              ICountableListener.DisabledListener.class,
                                              ICountableListener.NoSubtypesListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(ICountableListener.DefaultListener.class)
                             .handles(ICountable.class);

        expectedSubscriptions.listener(ICountableListener.DefaultListener.class)
                             .handles(MultipartMessage.class,
                                      IMultipartMessage.class,
                                      ICountable.class,
                                      StandardMessage.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    @Test
    public
    void testMultipleMessageListeners() {
        ListenerFactory listeners = listeners(ICountableListener.DefaultListener.class,
                                              ICountableListener.DisabledListener.class,
                                              IMultipartMessageListener.DefaultListener.class,
                                              IMultipartMessageListener.DisabledListener.class,
                                              MessageTypesListener.DefaultListener.class,
                                              MessageTypesListener.DisabledListener.class);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(ICountableListener.DefaultListener.class)
                             .handles(MultipartMessage.class,
                                      IMultipartMessage.class,
                                      ICountable.class,
                                      StandardMessage.class);

        expectedSubscriptions.listener(IMultipartMessageListener.DefaultListener.class)
                             .handles(MultipartMessage.class,
                                      IMultipartMessage.class);

        expectedSubscriptions.listener(MessageTypesListener.DefaultListener.class)
                             .handles(MessageTypes.class);

        runTestWith(listeners, expectedSubscriptions);
    }


    @Test
    public
    void testOverloadedMessageHandlers() {
        ListenerFactory listeners = listeners(Overloading.ListenerBase.class,
                                              Overloading.ListenerSub.class);

        final SubscriptionManager subscriptionManager = new SubscriptionManager(SubscriptionMode.StrongReferences);

        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(subscriptionManager, listeners), 1);

        SubscriptionValidator expectedSubscriptions = new SubscriptionValidator(listeners);

        expectedSubscriptions.listener(Overloading.ListenerBase.class)
                             .handles(Overloading.TestMessageA.class,
                                      Overloading.TestMessageA.class);

        expectedSubscriptions.listener(Overloading.ListenerSub.class)
                             .handles(Overloading.TestMessageA.class,
                                      Overloading.TestMessageA.class,
                                      Overloading.TestMessageB.class);

        runTestWith(listeners, expectedSubscriptions);
    }

    private ListenerFactory listeners(Class<?>... listeners) {
        ListenerFactory factory = new ListenerFactory();
        for (Class<?> listener : listeners) {
            factory.create(InstancesPerListener, listener);
        }
        return factory;
    }

    private void runTestWith(final ListenerFactory listeners, final SubscriptionValidator validator) {
        final SubscriptionManager subscriptionManager = new SubscriptionManager(SubscriptionMode.StrongReferences);

        ConcurrentExecutor.runConcurrent(TestUtil.subscriber(subscriptionManager, listeners), 1);

        validator.validate(subscriptionManager);

        ConcurrentExecutor.runConcurrent(TestUtil.unsubscriber(subscriptionManager, listeners), 1);

        listeners.clear();

        validator.validate(subscriptionManager);
    }
}

