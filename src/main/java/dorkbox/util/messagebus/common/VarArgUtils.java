package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.common.thread.SubscriptionHolder;
import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.subscription.SubscriptionUtils;

import java.util.ArrayList;
import java.util.Map;

public final class VarArgUtils {
    private final Map<Class<?>, ArrayList<Subscription>> varArgSubscriptions;
    private final Map<Class<?>, ArrayList<Subscription>> varArgSuperClassSubscriptions;
    private final HashMapTree<Class<?>, ArrayList<Subscription>> varArgSuperClassSubscriptionsMulti;

    private final SubscriptionHolder subHolderConcurrent;

    private final float loadFactor;
    private final int stripeSize;

    private final SubscriptionUtils utils;
    private final SuperClassUtils superClassUtils;
    private final Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle;


    public VarArgUtils(SubscriptionUtils utils, SuperClassUtils superClassUtils,
                       Map<Class<?>, ArrayList<Subscription>> subscriptionsPerMessageSingle, float loadFactor, int stripeSize) {

        this.utils = utils;
        this.superClassUtils = superClassUtils;
        this.subscriptionsPerMessageSingle = subscriptionsPerMessageSingle;
        this.loadFactor = loadFactor;
        this.stripeSize = stripeSize;

        this.varArgSubscriptions = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSuperClassSubscriptions = new ConcurrentHashMapV8<Class<?>, ArrayList<Subscription>>(16, loadFactor, stripeSize);
        this.varArgSuperClassSubscriptionsMulti = new HashMapTree<Class<?>, ArrayList<Subscription>>(4, loadFactor);

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
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, ArrayList<Subscription>> local = this.varArgSubscriptions;

        ArrayList<Subscription> varArgSubs = local.get(messageClass);

        if (varArgSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);

            ArrayList<Subscription> subs = this.subscriptionsPerMessageSingle.get(arrayVersion);
            if (subs != null) {
                final int length = subs.size();
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

        final Subscription[] subscriptions = new Subscription[varArgSubs.size()];
        varArgSubs.toArray(subscriptions);
        return subscriptions;
    }



    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass) {
        final ArrayList<Subscription> subs = getVarArgSuperSubscriptions_List(messageClass);

        final Subscription[] subscriptions = new Subscription[subs.size()];
        subs.toArray(subscriptions);
        return subscriptions;
    }

    private ArrayList<Subscription> getVarArgSuperSubscriptions_List(final Class<?> messageClass) {
        // whenever our subscriptions change, this map is cleared.
        final Map<Class<?>, ArrayList<Subscription>> local = this.varArgSuperClassSubscriptions;

        ArrayList<Subscription> varArgSuperSubs = local.get(messageClass);

        if (varArgSuperSubs == null) {
            // this gets (and caches) our array type. This is never cleared.
            final Class<?> arrayVersion = this.superClassUtils.getArrayClass(messageClass);
            final Class<?>[] types = this.superClassUtils.getSuperClasses(arrayVersion);

            final int typesLength = types.length;
            varArgSuperSubs = new ArrayList<Subscription>(typesLength);

            if (typesLength == 0) {
                local.put(messageClass, varArgSuperSubs);
                return varArgSuperSubs;
            }


            Class<?> type;
            Subscription sub;
            ArrayList<Subscription> subs;
            int length;

            for (int i = 0; i < typesLength; i++) {
                type = types[i];
                subs = this.subscriptionsPerMessageSingle.get(type);

                if (subs != null) {
                    length = subs.size();
                    varArgSuperSubs = new ArrayList<Subscription>(length);

                    for (int j = 0; j < length; j++) {
                        sub = subs.get(j);

                        if (sub.acceptsSubtypes() && sub.acceptsVarArgs()) {
                            varArgSuperSubs.add(sub);
                        }
                    }

                }
            }

            local.put(messageClass, varArgSuperSubs);
        }

        return varArgSuperSubs;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;

        HashMapTree<Class<?>, ArrayList<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2);
        ArrayList<Subscription> subsPerType;

        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        }
        else {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1);
            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2);

            final int size1 = varargSuperSubscriptions1.size();
            final int size2 = varargSuperSubscriptions2.size();

            subsPerType = new ArrayList<Subscription>(size1 + size2);

            Subscription sub;
            for (int i = 0; i < size1; i++) {
                sub = varargSuperSubscriptions1.get(i);

                if (varargSuperSubscriptions2.contains(sub)) {
                    subsPerType.add(sub);
                }
            }

            subsPerType.trimToSize();

            local.put(subsPerType, messageClass1, messageClass2);
        }

        final Subscription[] subscriptions = new Subscription[subsPerType.size()];
        subsPerType.toArray(subscriptions);
        return subscriptions;
    }


    // CAN NOT RETURN NULL
    // check to see if the messageType can convert/publish to the "array" superclass version, without the hit to JNI
    // and then, returns the array'd version superclass subscriptions
    public Subscription[] getVarArgSuperSubscriptions(final Class<?> messageClass1, final Class<?> messageClass2,
                                                      final Class<?> messageClass3) {
        // whenever our subscriptions change, this map is cleared.
        final HashMapTree<Class<?>, ArrayList<Subscription>> local = this.varArgSuperClassSubscriptionsMulti;

        HashMapTree<Class<?>, ArrayList<Subscription>> subsPerTypeLeaf = local.getLeaf(messageClass1, messageClass2, messageClass3);
        ArrayList<Subscription> subsPerType;

        if (subsPerTypeLeaf != null) {
            // if the leaf exists, then the value exists.
            subsPerType = subsPerTypeLeaf.getValue();
        }
        else {
            // the message class types are not the same, so look for a common superClass varArg subscription.
            // this is to publish to object[] (or any class[]) handler that is common among all superTypes of the messages
            final ArrayList<Subscription> varargSuperSubscriptions1 = getVarArgSuperSubscriptions_List(messageClass1);
            final ArrayList<Subscription> varargSuperSubscriptions2 = getVarArgSuperSubscriptions_List(messageClass2);
            final ArrayList<Subscription> varargSuperSubscriptions3 = getVarArgSuperSubscriptions_List(messageClass3);

            final int size1 = varargSuperSubscriptions1.size();
            final int size2 = varargSuperSubscriptions2.size();

            subsPerType = new ArrayList<Subscription>(size1 + size2);

            Subscription sub;
            for (int i = 0; i < size1; i++) {
                sub = varargSuperSubscriptions1.get(i);

                if (varargSuperSubscriptions2.contains(sub) && varargSuperSubscriptions3.contains(sub)) {
                    subsPerType.add(sub);
                }
            }

            subsPerType.trimToSize();

            local.put(subsPerType, messageClass1, messageClass2, messageClass3);
        }

        final Subscription[] subscriptions = new Subscription[subsPerType.size()];
        subsPerType.toArray(subscriptions);
        return subscriptions;
    }
}
