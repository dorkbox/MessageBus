/*
 * Copyright 2012 Benjamin Diedrichsen
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
package dorkbox.util.messagebus.common;

import java.util.Iterator;
import java.util.List;

import dorkbox.messageBus.MessageBus;
import dorkbox.messageBus.PubSubSupport;
import dorkbox.messageBus.subscription.SubscriptionManager;

/**
 * Todo: Add javadoc
 *
 * @author bennidi
 *         Date: 11/22/12
 */
public class TestUtil {


    public static Runnable subscriber(final SubscriptionManager manager, final ListenerFactory listeners) {
        final Iterator source = listeners.iterator();
        return new Runnable() {
            @Override public void run() {
                Object next;
                while ((next = source.next()) != null) {
                    manager.subscribe(next);
                }
            }
        };
    }

    public static Runnable unsubscriber(final SubscriptionManager manager, final ListenerFactory listeners) {
        final Iterator source = listeners.iterator();
        return new Runnable() {
            @Override public void run() {
                Object next;
                while ((next = source.next()) != null) {
                    manager.unsubscribe(next);
                }
            }
        };
    }

    public static Runnable subscriber(final PubSubSupport bus, final ListenerFactory listeners) {
        final Iterator source = listeners.iterator();
        return new Runnable() {
            @Override public void run() {
                Object next;
                while ((next = source.next()) != null) {
                    bus.subscribe(next);
                }
            }
        };
    }

    public static Runnable unsubscriber(final PubSubSupport bus, final ListenerFactory listeners) {
        final Iterator source = listeners.iterator();
        return new Runnable() {
            @Override public void run() {
                Object next;
                while ((next = source.next()) != null) {
                    bus.unsubscribe(next);
                }
            }
        };
    }

    public static void setup(final PubSubSupport bus, final List<Object> listeners, int numberOfThreads) {
        Runnable[] setupUnits = new Runnable[numberOfThreads];
        int partitionSize;
        if (listeners.size() >= numberOfThreads) {
            partitionSize = (int) Math.floor(listeners.size() / numberOfThreads);
        }
        else {
            partitionSize = 1;
            numberOfThreads = listeners.size();
        }

        for (int i = 0; i < numberOfThreads; i++) {
            final int partitionStart = i * partitionSize;
            final int partitionEnd = i + 1 < numberOfThreads ? partitionStart + partitionSize + 1 : listeners.size();
            setupUnits[i] = new Runnable() {

                private List<Object> listenerSubset = listeners.subList(partitionStart, partitionEnd);

                @Override public void run() {
                    for (Object listener : this.listenerSubset) {
                        bus.subscribe(listener);
                    }
                }
            };

        }

        ConcurrentExecutor.runConcurrent(setupUnits);

    }

    public static void setup(MessageBus bus, ListenerFactory listeners, int numberOfThreads) {
        setup(bus, listeners.getAll(), numberOfThreads);

    }
}
