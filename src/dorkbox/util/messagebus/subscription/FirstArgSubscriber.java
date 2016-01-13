/*
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
package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;
import dorkbox.util.messagebus.error.ErrorHandlingSupport;
import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Permits subscriptions that only use the first parameters as the signature. The publisher MUST provide the correct additional parameters,
 * and they must be of the correct type, otherwise it will throw an error.
 * </p>
 * Parameter length checking during publication is performed, so that you can have multiple handlers with the same signature, but each
 * with a different number of parameters
 */
public class FirstArgSubscriber implements Subscriber {

    private final ErrorHandlingSupport errorHandler;

    // all subscriptions per message type. We perpetually KEEP the types, as this lowers the amount of locking required
    // this is the primary list for dispatching a specific message
    // write access is synchronized and happens only when a listener of a specific class is registered the first time

    // the following are used ONLY for FIRST ARG subscription/publication. (subscriptionsPerMessageMulti isn't used in this case)
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessage;


    public FirstArgSubscriber(final ErrorHandlingSupport errorHandler) {
        this.errorHandler = errorHandler;

        // the following are used ONLY for FIRST ARG subscription/publication. (subscriptionsPerMessageMulti isn't used in this case)
        this.subscriptionsPerMessage = JavaVersionAdapter.concurrentMap(32, LOAD_FACTOR, 1);
    }

    // inside a write lock
    // add this subscription to each of the handled types
    // to activate this sub for publication
    @Override
    public void register(final Class<?> listenerClass, final int handlersSize, final Subscription[] subsPerListener) {

        final Map<Class<?>, ArrayList<Subscription>> subscriptions = this.subscriptionsPerMessage;

        Subscription subscription;
        MessageHandler handler;
        Class<?>[] messageHandlerTypes;
        int size;

        Class<?> type0;
        ArrayList<Subscription> subs;

        for (int i = 0; i < handlersSize; i++) {
            subscription = subsPerListener[i];

            // activate this subscription for publication
            // now add this subscription to each of the handled types

            // only register based on the FIRST parameter
            handler = subscription.getHandler();
            messageHandlerTypes = handler.getHandledMessages();
            size = messageHandlerTypes.length;

            if (size == 0) {
                errorHandler.handleError("Error while trying to subscribe class", listenerClass);
                continue;
            }

            type0 = messageHandlerTypes[0];
            subs = subscriptions.get(type0);
            if (subs == null) {
                subs = new ArrayList<Subscription>();

                subscriptions.put(type0, subs);
            }

            subs.add(subscription);
        }
    }

    @Override
    public AtomicBoolean getVarArgPossibility() {
        return null;
    }

    @Override
    public VarArgUtils getVarArgUtils() {
        return null;
    }

    @Override
    public void shutdown() {
        this.subscriptionsPerMessage.clear();
    }

    @Override
    public void clear() {

    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass) {
        return subscriptionsPerMessage.get(messageClass);
    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2) {
        return subscriptionsPerMessage.get(messageClass1);
    }

    @Override
    public ArrayList<Subscription> getExactAsArray(final Class<?> messageClass1, final Class<?> messageClass2,
                                                   final Class<?> messageClass3) {
        return subscriptionsPerMessage.get(messageClass1);
    }

    @Override
    public Subscription[] getExact(final Class<?> deadMessageClass) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExact(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return new Subscription[0];
    }


    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2) {
        return new Subscription[0];
    }

    @Override
    public Subscription[] getExactAndSuper(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3) {
        return new Subscription[0];
    }
}
