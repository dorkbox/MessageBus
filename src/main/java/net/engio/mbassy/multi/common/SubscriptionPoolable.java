package net.engio.mbassy.multi.common;

import java.util.ArrayDeque;
import java.util.Collection;

import net.engio.mbassy.multi.subscription.Subscription;
import dorkbox.util.objectPool.PoolableObject;

public class SubscriptionPoolable implements PoolableObject<Collection<Subscription>> {
    @Override
    public Collection<Subscription> create() {
        return new ArrayDeque<Subscription>(64);
    }
}
