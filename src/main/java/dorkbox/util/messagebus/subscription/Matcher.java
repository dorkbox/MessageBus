package dorkbox.util.messagebus.subscription;

public interface Matcher {
    void publish(Object messageClass) throws Throwable;
}
