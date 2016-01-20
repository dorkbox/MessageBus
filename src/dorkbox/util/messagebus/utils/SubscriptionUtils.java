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

import com.esotericsoftware.kryo.util.IdentityMap;
import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionManager;

import java.util.ArrayList;

public final
class SubscriptionUtils {
    private final ClassUtils superClass;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to shutdown() on the original one

    // keeps track of all subscriptions of the super classes of a message type.
    private volatile IdentityMap<Class<?>, Subscription[]> superClassSubscriptions;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> superClassSubscriptionsMulti;


    public
    SubscriptionUtils(final ClassUtils superClass, final float loadFactor, final int numberOfThreads) {
        this.superClass = superClass;


        // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.superClassSubscriptions = new IdentityMap<Class<?>, Subscription[]>(8, loadFactor);
        this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>();
    }

    public
    void clear() {
        this.superClassSubscriptions.clear();
        this.superClassSubscriptionsMulti.clear();
    }

//    // ALWAYS register and create a cached version of the requested class + superClasses
//    // ONLY called during subscribe
//    public
//    Subscription[] register(final Class<?> clazz, final SubscriptionManager subManager) {
//        final IdentityMap<Class<?>, Subscription[]> local = this.superClassSubscriptions;
//
//        // types was not empty, so collect subscriptions for each type and collate them
//
//        // save the subscriptions
//        final Class<?>[] superClasses = this.superClass.getSuperClasses(clazz);  // never returns null, cached response
//
//        Class<?> superClass;
//        Subscription[] superSubs;
//        Subscription sub;
//
//        final int length = superClasses.length;
//        int superSubLength;
//        final ArrayList<Subscription> subsAsList = new ArrayList<Subscription>(length);
//
//        for (int i = 0; i < length; i++) {
//            superClass = superClasses[i];
//            superSubs = subManager.getExactAsArray(superClass);
//
//            if (superSubs != null) {
//                superSubLength = superSubs.length;
//                for (int j = 0; j < superSubLength; j++) {
//                    sub = superSubs[j];
//
//                    if (sub.getHandler().acceptsSubtypes()) {
//                        subsAsList.add(sub);
//                    }
//                }
//            }
//        }
//
//        final int size = subsAsList.size();
//        if (size > 0) {
//            Subscription[] subs = new Subscription[size];
//            subsAsList.toArray(subs);
//            local.put(clazz, subs);
//
//            superClassSubscriptions = local;
//
//            return subs;
//        }
//
//        return null;
//    }

    /**
     * Returns an array COPY of the super subscriptions for the specified type.
     * <p/>
     * This ALSO checks to see if the superClass accepts subtypes.
     *
     * @return CAN RETURN NULL
     */
    public
    Subscription[] getSuperSubscriptions(final Class<?> clazz) {
        // whenever our subscriptions change, this map is cleared.
        return this.superClassSubscriptions.get(clazz);
    }

    /**
     * Returns an array COPY of the super subscriptions for the specified type.
     * <p/>
     * This ALSO checks to see if the superClass accepts subtypes.
     *
     * @return CAN NOT RETURN NULL
     */
    public
    ArrayList<Subscription> getSuperSubscriptions(final Class<?> clazz1, final Class<?> clazz2, final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> cached = this.superClassSubscriptionsMulti;

        ArrayList<Subscription> subs = cached.get(clazz1, clazz2);

        if (subs == null) {
            // types was not empty, so collect subscriptions for each type and collate them

            // save the subscriptions
            final Class<?>[] superClasses1 = this.superClass.getSuperClasses(clazz1);  // never returns null, cached response
            final Class<?>[] superClasses2 = this.superClass.getSuperClasses(clazz2);  // never returns null, cached response

            Class<?> superClass1;
            Class<?> superClass2;
            ArrayList<Subscription> superSubs;
            Subscription sub;

            final int length1 = superClasses1.length;
            final int length2 = superClasses2.length;

            subs = new ArrayList<Subscription>(length1 + length2);

            for (int i = 0; i < length1; i++) {
                superClass1 = superClasses1[i];

                // only go over subtypes
                if (superClass1 == clazz1) {
                    continue;
                }

                for (int j = 0; j < length2; j++) {
                    superClass2 = superClasses2[j];

                    // only go over subtypes
                    if (superClass2 == clazz2) {
                        continue;
                    }

                    superSubs = subManager.getExactAsArray(superClass1, superClass2);
                    if (superSubs != null) {
                        for (int k = 0; k < superSubs.size(); k++) {
                            sub = superSubs.get(k);

                            if (sub.getHandler().acceptsSubtypes()) {
                                subs.add(sub);
                            }
                        }
                    }
                }
            }
            subs.trimToSize();
            cached.put(subs, clazz1, clazz2);
        }

        return subs;
    }

    /**
     * Returns an array COPY of the super subscriptions for the specified type.
     * <p/>
     * This ALSO checks to see if the superClass accepts subtypes.
     *
     * @return CAN NOT RETURN NULL
     */
    public
    ArrayList<Subscription> getSuperSubscriptions(final Class<?> clazz1, final Class<?> clazz2, final Class<?> clazz3,
                                                  final SubscriptionManager subManager) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.superClassSubscriptionsMulti;

        ArrayList<Subscription> subs = local.get(clazz1, clazz2, clazz3);

        if (subs == null) {
            // types was not empty, so collect subscriptions for each type and collate them

            // save the subscriptions
            final Class<?>[] superClasses1 = this.superClass.getSuperClasses(clazz1);  // never returns null, cached response
            final Class<?>[] superClasses2 = this.superClass.getSuperClasses(clazz2);  // never returns null, cached response
            final Class<?>[] superClasses3 = this.superClass.getSuperClasses(clazz3);  // never returns null, cached response

            Class<?> superClass1;
            Class<?> superClass2;
            Class<?> superClass3;
            ArrayList<Subscription> superSubs;
            Subscription sub;

            final int length1 = superClasses1.length;
            final int length2 = superClasses2.length;
            final int length3 = superClasses3.length;

            subs = new ArrayList<Subscription>(length1 + length2 + length3);

            for (int i = 0; i < length1; i++) {
                superClass1 = superClasses1[i];

                // only go over subtypes
                if (superClass1 == clazz1) {
                    continue;
                }

                for (int j = 0; j < length2; j++) {
                    superClass2 = superClasses2[j];

                    // only go over subtypes
                    if (superClass2 == clazz2) {
                        continue;
                    }

                    for (int k = 0; k < length3; k++) {
                        superClass3 = superClasses3[j];

                        // only go over subtypes
                        if (superClass3 == clazz3) {
                            continue;
                        }

                        superSubs = subManager.getExactAsArray(superClass1, superClass2, superClass3);
                        if (superSubs != null) {
                            for (int m = 0; m < superSubs.size(); m++) {
                                sub = superSubs.get(m);

                                if (sub.getHandler().acceptsSubtypes()) {
                                    subs.add(sub);
                                }
                            }
                        }
                    }
                }
            }
            subs.trimToSize();
            local.put(subs, clazz1, clazz2, clazz3);
        }

        return subs;
    }
}
