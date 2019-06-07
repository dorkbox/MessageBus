package dorkbox.messageBus;

/**
 *
 */
public
enum SubscriptionMode {
    /**
     * This is the default.
     * <p>
     * We use strong references when saving the subscribed listeners (these are the classes & methods that receive messages).
     * <p>
     * In certain environments (ie: spring), it is desirable to use weak references -- so that there are no memory leaks during
     * the container lifecycle (or, more specifically, so one doesn't have to manually manage the memory).
     */
    StrongReferences,

    /**
     * Using weak references is a tad slower than using strong references, since there are additional steps taken when there are orphaned
     * references (when GC occurs) that have to be cleaned up. This cleanup occurs during message publication.
     */
    WeakReferences
}
