package dorkbox.util.messagebus.listeners;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import dorkbox.util.messagebus.annotations.Handler;


public class ObjectListener {

    private List handledMessages = Collections.synchronizedList(new LinkedList());

    @Handler()
    public void handle(Object message){
        this.handledMessages.add(message);
    }

}
