package dorkbox.util.messagebus.subscription;

public interface Matcher {
    boolean publish(Object messageClass) throws Throwable;
}
