package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.common.thread.ConcurrentSet;
import dorkbox.util.messagebus.common.thread.SubscriptionHolder;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VarArgUtils {
    private final Map<Class<?>, ArrayList<Subscription>> varArgSubscriptions;
    private final Map<Class<?>, List<Subscription>> varArgSuperClassSubscriptions;
    private final HashMapTree<Class<?>, ConcurrentSet<Subscription>> varArgSuperClassSubscriptionsMulti;

    private final SubscriptionHolder subHolderConcurrent;

    private final float loadFactor;
    private final int stripeSize;

    private final SubscriptionUtils utils;
    private final SuperClassUtils superClassUtils;
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle;


    public VarArgUtils(SubscriptionUtils utils, SuperClassUtils superClassUtils,
                       Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle, float loadFactor,
                       int stripeSize) {

        this.utils = utils;
        this.superClassUtils = superClassUtils;
        this.subscriptionsPerMessageSingle = subscriptionsPerMessageSingle;
        this.loadFactor = loadFactor;
        this.stripeSize = stripeSize;

        this.varArgSubscriptions = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSuperClassSubscriptions = new ConcurrentHashMapV8<Class<?>, List<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSuperClassSubscriptionsMulti = new HashMapTree<Class<?>, ConcurrentSet<Subscription>>(4, loadFactor);

        this.subHolderConcurrent = new SubscriptionHolder();
    }


    public void clear() {
        this.varArgSubscriptions.clear();
        this.varArgSuperClassSubscriptions.clear();
        this.varArgSuperClassSubscriptionsMulti.clear();
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Subscription[] getVarArgSubscriptions(Class<?> messageClass) {
        Map<Class<?>, ArrayList<Subscription>> local = this.varArgSubscriptions;

        ArrayList<Subscription> varArgSubs = local.get(messageClass);

        if (varArgSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);

            ArrayList<Subscription> subs = this.subscriptionsPerMessageSingle.get(arrayVersion);
            if (subs != null) {
                int length = subs.size();
                varArgSubs = new ArrayList<Subscription>(length);

                Subscription sub;
                for (int i = 0; i < length; i++) {
                    sub = subs.get(i);

                    if (sub.acceptsVarArgs()) {
                        varArgSubs.add(sub);
                    }
                }

                local.put(messageClass, varArgSubs);
            }
        }

//        return varArgSubs;

        return null;


        // whenever our subscriptions change, this map is cleared.
//        SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
//        ConcurrentSet<Subscription> subsPerType = subHolderConcurrent.publish();
//
//        // cache our subscriptions for super classes, so that their access can be fast!
//        ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(messageClass, subsPerType);
//        if (putIfAbsent == null) {
//            // we are the first one in the map
//            subHolderConcurrent.set(subHolderConcurrent.initialValue());
//
//
//            Iterator<Subscription> iterator;
//            Subscription sub;
//
//            Collection<Subscription> subs = this.subscriptionsPerMessageSingle.publish(arrayVersion);
//            if (subs != null) {
//                for (iterator = subs.iterator(); iterator.hasNext();) {
//                    sub = iterator.next();
//                    if (sub.acceptsVarArgs()) {
//                        subsPerType.add(sub);
//                    }
//                }
//            }
//            return subsPerType;
//        } else {
//            // someone beat us
//            return putIfAbsent;
//        }

//        return null;
    }



    // CAN  RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public Subscription[] getVarArgSuperSubscriptions(Class<?> messageClass) {
//        // whenever our subscriptions change, this map is cleared.
//        ConcurrentMap<Class<?>, ConcurrentSet<Subscription>> local = this.varArgSuperClassSubscriptions;
//
//        SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
//        ConcurrentSet<Subscription> subsPerType = subHolderConcurrent.publish();
//
//        // cache our subscriptions for super classes, so that their access can be fast!
//        ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(messageClass, subsPerType);
//
//        if (putIfAbsent == null) {
//            // we are the first one in the map
//            subHolderConcurrent.set(subHolderConcurrent.initialValue());
//
//            Class<?> arrayVersion = this.utils.getArrayClass(messageClass);
//            Collection<Class<?>> types = this.utils.getSuperClasses(arrayVersion, true);
//            if (types.isEmpty()) {
//                return subsPerType;
//            }
//
//            Map<Class<?>, Collection<Subscription>> local2 = this.subscriptionsPerMessageSingle;
//
//            Iterator<Class<?>> iterator;
//            Class<?> superClass;
//
//            Iterator<Subscription> subIterator;
//            Subscription sub;
//
//
//            for (iterator = types.iterator(); iterator.hasNext();) {
//                superClass = iterator.next();
//
//                Collection<Subscription> subs = local2.publish(superClass);
//                if (subs != null) {
//                    for (subIterator = subs.iterator(); subIterator.hasNext();) {
//                        sub = subIterator.next();
//                        if (sub.acceptsSubtypes() && sub.acceptsVarArgs()) {
//                            subsPerType.add(sub);
//                        }
//                    }
//                }
//            }
//            return subsPerType;
//        } else {
//            // someone beat us
//            return putIfAbsent;
//        }

        return null;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSuperSubscriptions(Class<?> messageClass1, Class<?> messageClass2) {
//        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;
//
//        // whenever our subscriptions change, this map is cleared.
//        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2);
//        ConcurrentSet<Subscription> subsPerType = null;
//
//        // we DO NOT care about duplicate, because the answers will be the same
//        if (subsPerTypeLeaf != null) {
//            // if the leaf exists, then the value exists.
//            subsPerType = subsPerTypeLeaf.getValue();
//        } else {
//            SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
//            subsPerType = subHolderConcurrent.publish();
//
//            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, messageClass1, messageClass2);
//            if (putIfAbsent != null) {
//                // someone beat us
//                subsPerType = putIfAbsent;
//            } else {
//                // the message class types are not the same, so look for a common superClass varArg subscription.
//                // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
//                ConcurrentSet<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions(messageClass1);
//                ConcurrentSet<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions(messageClass2);
//
//                Iterator<Subscription> iterator;
//                Subscription sub;
//
//                for (iterator = varargSuperSubscriptions1.iterator(); iterator.hasNext();) {
//                    sub = iterator.next();
//                    if (varargSuperSubscriptions2.contains(sub)) {
//                        subsPerType.add(sub);
//                    }
//                }
//
//                subHolderConcurrent.set(subHolderConcurrent.initialValue());
//            }
//        }
//
//        return subsPerType;
        return null;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version subscriptions
    public ConcurrentSet<Subscription> getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2,
                                                                   final Class<?> messageClass3) {
//        HashMapTree<Class<?>, ConcurrentSet<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;
//
//        // whenever our subscriptions change, this map is cleared.
//        HashMapTree<Class<?>, ConcurrentSet<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2, messageClass3);
//        ConcurrentSet<Subscription> subsPerType = null;
//
//        // we DO NOT care about duplicate, because the answers will be the same
//        if (subsPerTypeLeaf != null) {
//            // if the leaf exists, then the value exists.
//            subsPerType = subsPerTypeLeaf.getValue();
//        } else {
//            SubscriptionHolder subHolderConcurrent = this.subHolderConcurrent;
//            subsPerType = subHolderConcurrent.publish();
//
//            ConcurrentSet<Subscription> putIfAbsent = local.putIfAbsent(subsPerType, messageClass1, messageClass2, messageClass3);
//            if (putIfAbsent != null) {
//                // someone beat us
//                subsPerType = putIfAbsent;
//            } else {
//                // the message class types are not the same, so look for a common superClass varArg subscription.
//                // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
//                ConcurrentSet<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions(messageClass1);
//                ConcurrentSet<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions(messageClass2);
//                ConcurrentSet<Subscription> varargSuperSubscriptions3 = getVarArgSuperSubscriptions(messageClass3);
//
//                Iterator<Subscription> iterator;
//                Subscription sub;
//
//                for (iterator = varargSuperSubscriptions1.iterator(); iterator.hasNext();) {
//                    sub = iterator.next();
//                    if (varargSuperSubscriptions2.contains(sub) && varargSuperSubscriptions3.contains(sub)) {
//                        subsPerType.add(sub);
//                    }
//                }
//
//                subHolderConcurrent.set(subHolderConcurrent.initialValue());
//            }
//        }
//
//        return subsPerType;

        return null;
    }

}
