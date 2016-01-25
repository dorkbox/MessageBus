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
package dorkbox.util.messagebus.utils;

import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.MessageHandler;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionManager;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final
class VarArgUtils {
    private final Map<Class<?>, Subscription[]> varArgSubscriptionsSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> varArgSubscriptionsMulti;

    private final Map<Class<?>, Subscription[]> varArgSuperSubscriptionsSingle;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> varArgSuperSubscriptionsMulti;

    private final ClassUtils superClassUtils;


    public
    VarArgUtils(final ClassUtils superClassUtils, final float loadFactor, final int numberOfThreads) {

        this.superClassUtils = superClassUtils;

        this.varArgSubscriptionsSingle = new ConcurrentHashMap<Class<?>, Subscription[]>(16, loadFactor, numberOfThreads);
        this.varArgSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>();

        this.varArgSuperSubscriptionsSingle = new ConcurrentHashMap<Class<?>, Subscription[]>(16, loadFactor, numberOfThreads);
        this.varArgSuperSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>();
    }


    public
    void clear() {
        this.varArgSubscriptionsSingle.clear();
        this.varArgSubscriptionsMulti.clear();

        this.varArgSuperSubscriptionsSingle.clear();
        this.varArgSuperSubscriptionsMulti.clear();
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public
    Subscription[] getVarArgSubscriptions(final Class<?> messageClass, final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, Subscription[]> local = this.varArgSubscriptionsSingle;

        Subscription[] varArgSubs = local.get(messageClass);

        if (varArgSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);

            final Subscription[] subs = subManager.getSubs(arrayVersion);
            if (subs != null) {
                final int length = subs.length;
                final ArrayList<Subscription> varArgSubsAsList = new ArrayList<Subscription>(length);

                Subscription sub;
                for (int i = 0; i < length; i++) {
                    sub = subs[i];

                    if (sub.getHandler().acceptsVarArgs()) {
                        varArgSubsAsList.add(sub);
                    }
                }

                varArgSubs = new Subscription[varArgSubsAsList.size()];
                varArgSubsAsList.toArray(varArgSubs);

                local.put(messageClass, varArgSubs);
            }
            else {
                varArgSubs = new Subscription[0];
            }
        }

        return varArgSubs;
    }



    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public
    Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass, final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, Subscription[]> local = this.varArgSuperSubscriptionsSingle;

        Subscription[] varArgSuperSubs = local.get(messageClass);

        if (varArgSuperSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);
            final Class<?>[] types = this.superClassUtils.getSuperClasses(arrayVersion);

            final int typesLength = types.length;
            varArgSuperSubs = new Subscription[typesLength];

            if (typesLength == 0) {
                local.put(messageClass, varArgSuperSubs);
                return varArgSuperSubs;
            }


            // there are varArgs super classes for this messageClass
            Class<?> type;
            Subscription sub;
            Subscription[] subs;
            int length;
            MessageHandler handlerMetadata;

            for (int i = 0; i < typesLength; i++) {
                type = types[i];
                subs = subManager.getSubs(type);

                if (subs != null) {
                    length = subs.length;
                    final ArrayList<Subscription> varArgSuperSubsAsList = new ArrayList<Subscription>(length);

                    for (int j = 0; j < length; j++) {
                        sub = subs[j];

                        handlerMetadata = sub.getHandler();
                        if (handlerMetadata.acceptsSubtypes() && handlerMetadata.acceptsVarArgs()) {
                            varArgSuperSubsAsList.add(sub);
                        }
                    }

                    varArgSuperSubs = new Subscription[varArgSuperSubsAsList.size()];
                    varArgSuperSubsAsList.toArray(varArgSuperSubs);
                }
                else {
                    varArgSuperSubs = new Subscription[0];
                }
            }

            local.put(messageClass, varArgSuperSubs);
        }

        return varArgSuperSubs;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public
    Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2, final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperSubscriptionsMulti;

        ArrayList<Subscription> subs = local.get(messageClass1, messageClass2);

//        if (subs == null) {
//            // the message class types are not the same, so look for a common superClass varArg subscription.
//            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
//            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1, subManager);
//            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2, subManager);
//
//            subs = ClassUtils.findCommon(varargSuperSubscriptions1, varargSuperSubscriptions2);
//
//            subs.trimToSize();
//            local.put(subs, messageClass1, messageClass2);
//        }

        final Subscription[] returnedSubscriptions = new Subscription[subs.size()];
        subs.toArray(returnedSubscriptions);
        return returnedSubscriptions;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public
    Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2, final Class<?> messageClass3,
                                               final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperSubscriptionsMulti;

        ArrayList<Subscription> subs = local.get(messageClass1, messageClass2, messageClass3);

//        if (subs == null) {
//            // the message class types are not the same, so look for a common superClass varArg subscription.
//            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
//            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1, subManager);
//            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2, subManager);
//            final ArrayList<Subscription> varargSuperSubscriptions3 = getVarArgSuperSubscriptions_List(messageClass3, subManager);
//
//            subs = ClassUtils.findCommon(varargSuperSubscriptions1, varargSuperSubscriptions2);
//            subs = ClassUtils.findCommon(subs, varargSuperSubscriptions3);
//
//            subs.trimToSize();
//            local.put(subs, messageClass1, messageClass2, messageClass3);
//        }

        final Subscription[] returnedSubscriptions = new Subscription[subs.size()];
        subs.toArray(returnedSubscriptions);
        return returnedSubscriptions;
    }
}
