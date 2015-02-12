package net.engio.mbassy.multi.listeners;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import net.engio.mbassy.multi.annotations.Handler;


public class ObjectListener {

    private List handledMessages = Collections.synchronizedList(new LinkedList());

    @Handler()
    public void handle(Object message){
        this.handledMessages.add(message);
    }

}
