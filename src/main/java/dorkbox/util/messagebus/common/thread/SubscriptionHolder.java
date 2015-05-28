package dorkbox.util.messagebus.common.thread;

import java.util.ArrayList;

import dorkbox.util.messagebus.subscription.Subscription;

public class SubscriptionHolder extends ThreadLocal<ArrayList<Subscription>> {

    public SubscriptionHolder() {
        super();
    }

    @Override
    public ArrayList<Subscription> initialValue() {
        return new ArrayList<Subscription>();
    }
}

