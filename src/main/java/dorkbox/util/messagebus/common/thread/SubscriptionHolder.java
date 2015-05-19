package dorkbox.util.messagebus.common.thread;

import dorkbox.util.messagebus.common.StrongConcurrentSetV8;
import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionHolder extends ThreadLocal<StrongConcurrentSetV8<Subscription>> {

    private final float loadFactor;

    public SubscriptionHolder(float loadFactor) {
        super();

        this.loadFactor = loadFactor;
    }

    @Override
    public StrongConcurrentSetV8<Subscription> initialValue() {
        return new StrongConcurrentSetV8<Subscription>(16, this.loadFactor);
    }
}

