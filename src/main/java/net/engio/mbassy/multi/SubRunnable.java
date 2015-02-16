package net.engio.mbassy.multi;

import java.lang.reflect.Method;


/**
 * @author dorkbox, llc Date: 2/2/15
 */
public class SubRunnable {

    public Method handler;
    public Object listener;
    public Object message;

    public SubRunnable(Method handler, Object listener, Object message) {
        this.handler = handler;
        this.listener = listener;
        this.message = message;
    }
}
