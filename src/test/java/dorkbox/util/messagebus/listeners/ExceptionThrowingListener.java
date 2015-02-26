package dorkbox.util.messagebus.listeners;

import dorkbox.util.messagebus.annotations.Handler;
import dorkbox.util.messagebus.annotations.Listener;
import dorkbox.util.messagebus.messages.StandardMessage;

/**
 * @author bennidi
 *         Date: 5/25/13
 */
@Listener()
public class ExceptionThrowingListener {


    // this handler will be invoked asynchronously
    @Handler()
    public void handle(StandardMessage message) {
        throw new RuntimeException("This is an expected exception");
    }


}
