package dorkbox.util.messagebus.common;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import dorkbox.util.messagebus.common.thread.ClassHolder;
import dorkbox.util.messagebus.common.thread.ConcurrentSet;
import dorkbox.util.messagebus.common.thread.SubscriptionHolder;
import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionUtils {

    private final Map<Class<?>, Class<?>> arrayVersionCache;
    private final Map<Class<?>, Boolean> isArrayCache;

    private final ConcurrentMap<Class<?>, ConcurrentSet<Class<?>>> superClassesCache;
    private final ClassHolder classHolderSingle;

    // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
    // it's a hit on SUB/UNSUB, but REALLY improves performance on handlers
    // it's faster to create a new one for SUB/UNSUB than it is to clear() on the original one
    private final ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> superClassSubscriptions;
    private final HashMapTree<Class<?>, ConcurrentSet<Subscription>> superClassSubscriptionsMulti;

    private final Map<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle;


    private final SubscriptionHolder subHolderSingle;
    private final SubscriptionHolder subHolderConcurrent;
    private final HashMapTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti;


    public SubscriptionUtils(Map<Class<?>, Collection<Subscription>> subscriptionsPerMessageSingle,
                             HashMapTree<Class<?>, Collection<Subscription>> subscriptionsPerMessageMulti,
                             float loadFactor, int stripeSize) {

        this.subscriptionsPerMessageSingle = subscriptionsPerMessageSingle;
        this.subscriptionsPerMessageMulti = subscriptionsPerMessageMulti;
        this.arrayVersionCache = new ConcurrentHashMapV8<Class<?>, Class<?>>(32, loadFactor, stripeSize);
        this.isArrayCache = new ConcurrentHashMapV8<Class<?>, Boolean>(32, loadFactor, stripeSize);

        this.superClassesCache = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Class<?>>>(32, loadFactor, stripeSize);
        this.classHolderSingle = new ClassHolder(loadFactor, stripeSize);

        // superClassSubscriptions keeps track of all subscriptions of super classes. SUB/UNSUB dumps it, so it is recreated dynamically.
        // it's a hit on SUB/UNSUB, but improves performance of handlers
        this.superClassSubscriptions = new ConcurrentHashMapV8<Class<?>, ConcurrentSet<Subscription>>(32, loadFactor, stripeSize);
        this.superClassSubscriptionsMulti = new HashMapTree<Class<?>, ConcurrentSet<Subscription>>(4, loadFactor);

        this.subHolderSingle = new SubscriptionHolder(loadFactor, stripeSize);
        this.subHolderConcurrent = new SubscriptionHolder(loadFactor, stripeSize);
    }

    public void clear() {
        this.superClassSubscriptions.clear();
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset, since it never needs to be reset (as the class hierarchy doesn't change at runtime)
     */
    public Collection<Class<?>> getSuperClasses(Class<?> clazz) {
        return getSuperClasses(clazz, isArray(clazz));
    }

    public final Collection<Class<?>> getSuperClasses(Class<?> clazz, boolean isArray) {
        // this is never reset, since it never needs to be.
        ConcurrentMap<Class<?>, ConcurrentSet<Class<?>>> local = this.superClassesCache;

        ClassHolder classHolderSingle = this.classHolderSingle;
        ConcurrentSet<Class<?>> classes = classHolderSingle.get();

        ConcurrentSet<Class<?>> putIfAbsent = local.putIfAbsent(clazz, classes);
        if (putIfAbsent == null) {
            // we are the first one in the map
            classHolderSingle.set(classHolderSingle.initialValue());

            // it doesn't matter if concurrent access stomps on values, since they are always the same.
            Collection<Class<?>> superTypes = ReflectionUtils.getSuperTypes(clazz);
            Iterator<Class<?>> iterator;
            Class<?> c;

            for (iterator = superTypes.iterator(); iterator.hasNext();) {
                c = iterator.next();
                if (isArray) {
                    c = getArrayClass(c);
                }

                if (c != clazz) {
                    classes.add(c);
                }
            }

            return classes;
        } else {
            // someone beat us
            return putIfAbsent;
        }
    }

    /**
     * race conditions will result in duplicate answers, which we don't care if happens
     * never returns null
     * never reset
     */
    public final Class<?> getArrayClass(Class<?> c) {
        Map<Class<?>, Class<?>> arrayVersionCache = this.arrayVersionCache;
        Class<?> clazz = arrayVersionCache.get(c);
        if (clazz == null) {
            // messy, but the ONLY way to do it. Array super types are also arrays
            Object[] newInstance = (Object[]) Array.newInstance(c, 1);
            clazz = newInstance.getClass();
            arrayVersionCache.put(c, clazz);
        }

        return clazz;
    }

    /**
     * Cache the values of JNI method, isArray(c)
     * @return true if the class c is an array type
     */
    @SuppressWarnings("boxing")
    public final boolean isArray(Class<?> c) {
        Map<Class<?>, Boolean> isArrayCache = this.isArrayCache;

        Boolean isArray = isArrayCache.get(c);
        if (isArray == null) {
            boolean b = c.isArray();
            isArrayCache.put(c, b);
            return b;
        }
        return isArray;
    }


    public void shutdown() {
        this.isArrayCache.clear();
        this.arrayVersionCache.clear();
        this.superClassesCache.clear();
    }


 // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public final Collection<Subscription> getSuperSubscriptions(Class<?> superType) {
        // whenever our subscriptions change, this map is cleared.
        ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptions;

        SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
        ConcurrentSet<Subscription> subsPerType = subHolderConcurrent.get();

        // cache our subscriptions for super classes, so that their access can be fast!
        ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(superType, subsPerType);
        if (putIfAbsent == null) {
            // we are the first one in the map
            subHolderConcurrent.set(subHolderConcurrent.initialValue());

            Collection<Class<?>> types = getSuperClasses(superType);
            if (types.isEmpty()) {
                return subsPerType;
            }

            Map<Class<?>, Collection<Subscription>> local2 = this.subscriptionsPerMessageSingle;

            Class<?> superClass;
            Iterator<Class<?>> iterator;

            Iterator<Subscription> subIterator;
            Subscription sub;

            for (iterator = types.iterator(); iterator.hasNext();) {
                superClass = iterator.next();

                Collection<Subscription> subs = local2.get(superClass);
                if (subs != null) {
                    for (subIterator = subs.iterator(); subIterator.hasNext();) {
                        sub = subIterator.next();
                        if (sub.acceptsSubtypes()) {
                            subsPerType.add(sub);
                        }
                    }
                }
            }
            return subsPerType;
        } else {
            // someone beat us
            return putIfAbsent;
        }
    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2) {
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2);
        ConcurrentSet<Subscription> subsPerType = null;

        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            SubscriptionHolder subHolderSingle = this.subHolderSingle;
            subsPerType = subHolderSingle.get();

            // cache our subscriptions for super classes, so that their access can be fast!
            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2);
            if (putIfAbsent == null) {
                // we are the first one in the map
                subHolderSingle.set(subHolderSingle.initialValue());

                // whenever our subscriptions change, this map is cleared.
                Collection<Class<?>> types1 = getSuperClasses(superType1);

                if (types1 != null) {
                    Collection<Class<?>> types2 = getSuperClasses(superType2);

                    Collection<Subscription> subs;
                    HashMapTree<Class<?>, Collection<Subscription>> leaf1;
                    HashMapTree<Class<?>, Collection<Subscription>> leaf2;

                    Class<?> eventSuperType1;
                    Class<?> eventSuperType2;

                    Iterator<Class<?>> iterator1;
                    Iterator<Class<?>> iterator2;

                    Iterator<Subscription> subIterator;
                    Subscription sub;

                    for (iterator1 = types1.iterator(); iterator1.hasNext();) {
                        eventSuperType1 = iterator1.next();

                        boolean type1Matches = eventSuperType1 == superType1;
                        if (type1Matches) {
                            continue;
                        }

                        leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                        if (leaf1 != null && types2 != null) {
                            for (iterator2 = types2.iterator(); iterator2.hasNext();) {
                                eventSuperType2 = iterator2.next();

                                if (type1Matches && eventSuperType2 == superType2) {
                                    continue;
                                }

                                leaf2 = leaf1.getLeaf(eventSuperType2);

                                if (leaf2 != null) {
                                    subs = leaf2.getValue();
                                    if (subs != null) {
                                        for (subIterator = subs.iterator(); subIterator.hasNext();) {
                                            sub = subIterator.next();
                                            if (sub.acceptsSubtypes()) {
                                                subsPerType.add(sub);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }

    // CAN NOT RETURN NULL
    // ALSO checks to see if the superClass accepts subtypes.
    public Collection<Subscription> getSuperSubscriptions(Class<?> superType1, Class<?> superType2, Class<?> superType3) {
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.superClassSubscriptionsMulti;

        // whenever our subscriptions change, this map is cleared.
        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(superType1, superType2, superType3);
        ConcurrentSet<Subscription> subsPerType;


        // we DO NOT care about duplicate, because the answers will be the same
        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        } else {
            SubscriptionHolder subHolderSingle = this.subHolderSingle;
            subsPerType = subHolderSingle.get();

            // cache our subscriptions for super classes, so that their access can be fast!
            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, superType1, superType2, superType3);
            if (putIfAbsent == null) {
                // we are the first one in the map
                subHolderSingle.set(subHolderSingle.initialValue());

                Collection<Class<?>> types1 = getSuperClasses(superType1);

                if (types1 != null) {
                    Collection<Class<?>> types2 = getSuperClasses(superType2);
                    Collection<Class<?>> types3 = getSuperClasses(superType3);

                    Collection<Subscription> subs;
                    HashMapTree<Class<?>, Collection<Subscription>> leaf1;
                    HashMapTree<Class<?>, Collection<Subscription>> leaf2;
                    HashMapTree<Class<?>, Collection<Subscription>> leaf3;

                    Class<?> eventSuperType1;
                    Class<?> eventSuperType2;
                    Class<?> eventSuperType3;

                    Iterator<Class<?>> iterator1;
                    Iterator<Class<?>> iterator2;
                    Iterator<Class<?>> iterator3;

                    Iterator<Subscription> subIterator;
                    Subscription sub;

                    for (iterator1 = types1.iterator(); iterator1.hasNext();) {
                        eventSuperType1 = iterator1.next();

                        boolean type1Matches = eventSuperType1 == superType1;
                        if (type1Matches) {
                            continue;
                        }

                        leaf1 = this.subscriptionsPerMessageMulti.getLeaf(eventSuperType1);
                        if (leaf1 != null && types2 != null) {
                            for (iterator2 = types2.iterator(); iterator2.hasNext();) {
                                eventSuperType2 = iterator2.next();

                                boolean type12Matches = type1Matches && eventSuperType2 == superType2;
                                if (type12Matches) {
                                    continue;
                                }

                                leaf2 = leaf1.getLeaf(eventSuperType2);

                                if (leaf2 != null && types3 != null) {
                                    for (iterator3 = types3.iterator(); iterator3.hasNext();) {
                                        eventSuperType3 = iterator3.next();

                                        if (type12Matches && eventSuperType3 == superType3) {
                                            continue;
                                        }

                                        leaf3 = leaf2.getLeaf(eventSuperType3);

                                        if (leaf3 != null) {
                                            subs = leaf3.getValue();
                                            if (subs != null) {
                                                for (subIterator = subs.iterator(); subIterator.hasNext();) {
                                                    sub = subIterator.next();
                                                    if (sub.acceptsSubtypes()) {
                                                        subsPerType.add(sub);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // someone beat us
                subsPerType = putIfAbsent;
            }
        }

        return subsPerType;
    }
}
