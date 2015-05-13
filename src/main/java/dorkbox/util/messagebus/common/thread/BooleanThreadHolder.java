package dorkbox.util.messagebus.common.thread;


public class BooleanThreadHolder extends ThreadLocal<BooleanHolder> {

    public BooleanThreadHolder() {
        super();
    }

    @Override
    public BooleanHolder initialValue() {
        return new BooleanHolder();
    }
}

