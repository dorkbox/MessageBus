/*
 * Copyright 2013 Benjamin Diedrichsen
 *
 * Permission is hereby granted, free  of charge, to any person obtaining
 * a  copy  of this  software  and  associated  documentation files  (the
 * "Software"), to  deal in  the Software without  restriction, including
 * without limitation  the rights to  use, copy, modify,  merge, publish,
 * distribute,  sublicense, and/or sell  copies of  the Software,  and to
 * permit persons to whom the Software  is furnished to do so, subject to
 * the following conditions:
 *
 * The  above  copyright  notice  and  this permission  notice  shall  be
 * included in all copies or substantial portions of the Software.
 *
 * THE  SOFTWARE IS  PROVIDED  "AS  IS", WITHOUT  WARRANTY  OF ANY  KIND,
 * EXPRESS OR  IMPLIED, INCLUDING  BUT NOT LIMITED  TO THE  WARRANTIES OF
 * MERCHANTABILITY,    FITNESS    FOR    A   PARTICULAR    PURPOSE    AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE,  ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package dorkbox.util.messagebus.listeners;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User: benjamin
 * Date: 6/26/13
 */
public class Listeners {

    private static final List<Class> Synchronous = Collections.unmodifiableList(Arrays.asList(new Class[]{
            MessagesListener.DefaultListener.class,
            IMessageListener.DefaultListener.class,
            StandardMessageListener.DefaultListener.class,
            MultipartMessageListener.DefaultListener.class,
            ICountableListener.DefaultListener.class,
            IMultipartMessageListener.DefaultListener.class}));

    private static final List<Class> SubtypeRejecting = Collections.unmodifiableList(Arrays.asList(new Class[]{
            MessagesListener.NoSubtypesListener.class,
            IMessageListener.NoSubtypesListener.class,
            StandardMessageListener.NoSubtypesListener.class,
            MultipartMessageListener.NoSubtypesListener.class,
            ICountableListener.NoSubtypesListener.class,
            IMultipartMessageListener.NoSubtypesListener.class}));

    private static final List<Class> NoHandlers = Collections.unmodifiableList(Arrays.asList(new Class[]{
            MessagesListener.DisabledListener.class,
            IMessageListener.DisabledListener.class,
            StandardMessageListener.DisabledListener.class,
            MultipartMessageListener.DisabledListener.class,
            ICountableListener.DisabledListener.class,
            IMultipartMessageListener.DisabledListener.class,
            Object.class,String.class}));


    private static final List<Class> HandlesIMessage = Collections.unmodifiableList(Arrays.asList(new Class[]{
            IMessageListener.DefaultListener.class,
            IMessageListener.NoSubtypesListener.class,
            IMultipartMessageListener.DefaultListener.class,
            IMultipartMessageListener.NoSubtypesListener.class,
            MessagesListener.DefaultListener.class,
            MessagesListener.NoSubtypesListener.class,
            StandardMessageListener.DefaultListener.class,
            StandardMessageListener.NoSubtypesListener.class,
            MultipartMessageListener.DefaultListener.class,
            MultipartMessageListener.NoSubtypesListener.class}));

    private static final List<Class> HandlesStandardessage = Collections.unmodifiableList(Arrays.asList(new Class[]{
            IMessageListener.DefaultListener.class,
            ICountableListener.DefaultListener.class,
            StandardMessageListener.DefaultListener.class,
            StandardMessageListener.NoSubtypesListener.class}));


    public static Collection<Class> synchronous(){
        return Synchronous;
    }

    public static Collection<Class> subtypeRejecting(){
        return SubtypeRejecting;
    }

    public static Collection<Class> noHandlers(){
        return NoHandlers;
    }

    public static Collection<Class> handlesIMessage(){
        return HandlesIMessage;
    }

    public static Collection<Class> handlesStandardMessage(){
        return HandlesStandardessage;
    }


    public static Collection<Class> join(Collection<Class>...listenerSets){
        Set<Class> join = new HashSet<Class>();
        for(Collection<Class> listeners : listenerSets) {
            join.addAll(listeners);
        }
        for(Collection<Class> listeners : listenerSets) {
            join.retainAll(listeners);
        }
        return join;
    }




}
