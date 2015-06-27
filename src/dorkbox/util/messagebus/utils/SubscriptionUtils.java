package dorkbox.util.messagebus.utils;

import dorkbox.util.messagebus.common.HashMapTree;
import dorkbox.util.messagebus.common.adapter.JavaVersionAdapter;
import dorkbox.util.messagebus.subscription.Subscriber;
import dorkbox.util.messagebus.subscription.Subscription;

import java.util.ArrayList;
import java.util.Map;

public final
class SubscriptionUtils {
    private final ClassUtils superClass;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to shutdown() on the original one
    private final Map<Class<?>, ArrayList<Subscription>> superClassSubscriptions;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> superClassSubscriptionsMulti;


    public
    SubscriptionUtils(final ClassUtils superClass, final float loadFactor) {
        this.superClass = superClass;


        // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.superClassSubscriptions = JavaVersionAdapter.concurrentMap(8, loadFactor, 1);
        this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, loadFactor);
    }

    public
    void clear() {
        this.superClassSubscriptions.clear();
        this.superClassSubscriptionsMulti.clear();
    }


    /**
     * Returns an array COPY of the super subscriptions for the specified type.
     * <p/>
     * This ALSO checks to see if the superClass accepts subtypes.
     * <p/>
     * protected by read lock by caller
     *
     * @return CAN NOT RETURN NULL
     */
    public
    ArrayList<Subscription> getSuperSubscriptions(final Class<?> clazz, final Subscriber subscriber) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, ArrayList<Subscription>> local = this.superClassSubscriptions;

        ArrayList<Subscription> subs = local.get(clazz);

        if (subs == null) {
            // types was not empty, so collect subscriptions for each type and collate them

            // save the subscriptions
            final Class<?>[] superClasses = this.superClass.getSuperClasses(clazz);  // never returns null, cached response

            Class<?> superClass;
            ArrayList<Subscription> superSubs;
            Subscription sub;

            final int length = superClasses.length;
            int superSubLength;
            subs = new ArrayList<Subscription>(length);

            for (int i = 0; i < length; i++) {
                superClass = superClasses[i];
                superSubs = subscriber.getExactAsArray(superClass);

                if (superSubs != null) {
                    superSubLength = superSubs.size();
                    for (int j = 0; j < superSubLength; j++) {
                        sub = superSubs.get(j);

                        if (sub.getHandler().acceptsSubtypes()) {
                            subs.add(sub);
                        }
                    }
                }
            }

            subs.trimToSize();
            local.put(clazz, subs);
        }

        return subs;
    }

    /**
     * Returns an array COPY of the super subscriptions for the specified type.
     * <p/>
     * This ALSO checks to see if the superClass accepts subtypes.
     * <p/>
     * protected by read lock by caller
     *
     * @return CAN NOT RETURN NULL
     */
    public
    ArrayList<Subscription> getSuperSubscriptions(final Class<?> clazz1, final Class<?> clazz2, final Subscriber subscriber) {
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

                    superSubs = subscriber.getExactAsArray(superClass1, superClass2);
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
     * <p/>
     * protected by read lock by caller
     *
     * @return CAN NOT RETURN NULL
     */
    public
    ArrayList<Subscription> getSuperSubscriptions(final Class<?> clazz1, final Class<?> clazz2, final Class<?> clazz3,
                                                  final Subscriber subscriber) {
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

                        superSubs = subscriber.getExactAsArray(superClass1, superClass2, superClass3);
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
