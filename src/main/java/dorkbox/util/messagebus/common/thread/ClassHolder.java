package dorkbox.util.messagebus.common.thread;

import dorkbox.util.messagebus.common.StrongConcurrentSetV8;


public class ClassHolder extends ThreadLocal<StrongConcurrentSetV8<Class<?>>> {

    private final int stripeSize;
    private final float loadFactor;

    public ClassHolder(float loadFactor, int stripeSize) {
        super();

        this.stripeSize = stripeSize;
        this.loadFactor = loadFactor;
    }

    @Override
    public StrongConcurrentSetV8<Class<?>> initialValue() {
        return new StrongConcurrentSetV8<Class<?>>(16, this.loadFactor, this.stripeSize);
    }
}

