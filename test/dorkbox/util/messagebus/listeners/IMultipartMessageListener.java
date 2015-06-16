package dorkbox.util.messagebus.listeners;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.messages.IMultipartMessage;

/**
 *
 * @author bennidi
 *         Date: 5/24/13
 */
public class IMultipartMessageListener {

    private static abstract class BaseListener {

        @Handler
        public void handle(IMultipartMessage message){
            message.handled(this.getClass());
        }

    }

    public static class DefaultListener extends BaseListener {

        @Override
        public void handle(IMultipartMessage message){
            super.handle(message);
        }
    }

    public static class NoSubtypesListener extends BaseListener {

        @Override
        @Handler(acceptSubtypes = false)
        public void handle(IMultipartMessage message){
            super.handle(message);
        }
    }


    public static class DisabledListener extends BaseListener {

        @Override
        @Handler(enabled = false)
        public void handle(IMultipartMessage message){
            super.handle(message);
        }

    }


}
