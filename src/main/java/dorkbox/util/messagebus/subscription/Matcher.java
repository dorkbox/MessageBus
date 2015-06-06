package dorkbox.util.messagebus.subscription;

public interface Matcher {
    void publish(Object message) throws Throwable;

    void publish(Object message1, Object message2) throws Throwable;

    void publish(Object message1, Object message2, Object message3) throws Throwable;

    void publish(Object[] messages) throws Throwable;
}
