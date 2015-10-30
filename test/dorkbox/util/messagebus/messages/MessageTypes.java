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
package dorkbox.util.messagebus.messages;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Enum used to test handlers that consume enumerations.
 *
 * @author bennidi
 *         Date: 5/24/13
 */
public enum MessageTypes implements IMessage{

    Simple,
    Persistent,
    Multipart;

    private Map<Class, Integer> handledByListener = new HashMap<Class, Integer>();
    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static void resetAll(){
        for(MessageTypes m : values())
            m.reset();
    }


    @Override
    public void reset() {
        try {
            lock.writeLock().lock();
            handledByListener.clear();
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void handled(Class listener) {
        try {
            lock.writeLock().lock();
            Integer count = handledByListener.get(listener);
            if(count == null){
                handledByListener.put(listener, 1);
            }
            else{
                handledByListener.put(listener, count + 1);
            }
        }finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int getTimesHandled(Class listener) {
        try {
            lock.readLock().lock();
            return handledByListener.containsKey(listener)
                    ? handledByListener.get(listener)
                    : 0;
        }finally {
            lock.readLock().unlock();
        }
    }
}
