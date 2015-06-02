package dorkbox.util.messagebus.subscription;

public interface Matcher {
    Subscription[] getSubscriptions(Class<?> messageClass);
}
