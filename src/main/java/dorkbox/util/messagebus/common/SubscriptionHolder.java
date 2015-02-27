package dorkbox.util.messagebus.common;

import java.util.Collection;

import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionHolder extends ThreadLocal<Collection<Subscription>> {

    private final int stripeSize;
    private final float loadFactor;

    public SubscriptionHolder(float loadFactor, int stripeSize) {
        super();

        this.stripeSize = stripeSize;
        this.loadFactor = loadFactor;
    }

    @Override
    protected Collection<Subscription> initialValue() {
        return new StrongConcurrentSetV8<Subscription>(16, this.loadFactor, this.stripeSize);
    }
}

