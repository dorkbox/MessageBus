package dorkbox.util.messagebus.listeners;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Listener;
import dorkbox.util.messagebus.messages.AbstractMessage;

/**
 * Some handlers and message types to test correct functioning of overloaded
 * message handlers
 *
 */
public class Overloading {

    public static class TestMessageA extends AbstractMessage {}

    public static class TestMessageB extends AbstractMessage {}

    public static class ListenerSub extends ListenerBase {

        @Handler
        public void handleEvent(TestMessageB event) {
            event.handled(this.getClass());
        }

    }

    @Listener()
    public static class ListenerBase {


        /**
         * (!) If this method is removed, NO event handler will be called.
         */
        @Handler
        public void handleEventWithNonOverloadedMethodName(TestMessageA event) {
            event.handled(this.getClass());
        }

        @Handler
        public void handleEvent(TestMessageA event) {
            event.handled(this.getClass());
        }

    }

}
