package dorkbox.util.messagebus.common;

import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionHolder extends ThreadLocal<StrongConcurrentSetV8<Subscription>> {

    private final int stripeSize;
    private final float loadFactor;

    public SubscriptionHolder(float loadFactor, int stripeSize) {
        super();

        this.stripeSize = stripeSize;
        this.loadFactor = loadFactor;
    }

    @Override
    protected StrongConcurrentSetV8<Subscription> initialValue() {
        return new StrongConcurrentSetV8<Subscription>(16, this.loadFactor, this.stripeSize);
    }
}

