package dorkbox.util.messagebus.common.thread;



public class ClassHolder extends ThreadLocal<ConcurrentSet<Class<?>>> {

    private final float loadFactor;
    private final int stripeSize;

    public ClassHolder(float loadFactor, int stripeSize) {
        super();

        this.loadFactor = loadFactor;
        this.stripeSize = stripeSize;
    }

    @Override
    public ConcurrentSet<Class<?>> initialValue() {
        return new ConcurrentSet<Class<?>>(16, this.loadFactor, this.stripeSize);
    }
}

