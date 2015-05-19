package dorkbox.util.messagebus.common.thread;

import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionHolder extends ThreadLocal<ConcurrentSet<Subscription>> {

    private final float loadFactor;
    private final int stripeSize;

    public SubscriptionHolder(float loadFactor, int stripeSize) {
        super();

        this.loadFactor = loadFactor;
        this.stripeSize = stripeSize;
    }

    @Override
    public ConcurrentSet<Subscription> initialValue() {
        return new ConcurrentSet<Subscription>(16, this.loadFactor, this.stripeSize);
    }
}

