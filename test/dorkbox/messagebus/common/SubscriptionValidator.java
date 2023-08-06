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
package dorkbox.messagebus.common;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import dorkbox.messageBus.subscription.Subscription;
import dorkbox.messageBus.subscription.SubscriptionManager;

/**
 * @author bennidi
 *         Date: 5/25/13
 */
public class SubscriptionValidator extends AssertSupport {

    public class Expectation {
        private Class listener;

        private Expectation(Class listener) {
            this.listener = listener;
        }

        public SubscriptionValidator handles(Class... messages) {
            for (Class message : messages) {
                expect(this.listener, message);
            }

            return SubscriptionValidator.this;
        }
    }

    private class ValidationEntry {
        private Class subscriber;
        private Class messageType;

        private ValidationEntry(Class messageType, Class subscriber) {
            this.messageType = messageType;
            this.subscriber = subscriber;
        }
    }

    private List<ValidationEntry> validations = new LinkedList<ValidationEntry>();
    private Set<Class> messageTypes = new HashSet<Class>();
    private ListenerFactory subscribedListener; // the subscribed listeners are used to assert the size of the subscriptions

    public SubscriptionValidator(ListenerFactory subscribedListener) {
        this.subscribedListener = subscribedListener;
    }

    public Expectation listener(Class subscriber) {
        return new Expectation(subscriber);
    }

    private SubscriptionValidator expect(Class subscriber, Class messageType) {
        this.validations.add(new ValidationEntry(messageType, subscriber));
        this.messageTypes.add(messageType);

        return this;
    }

    private Collection<ValidationEntry> getEntries(Class<?> messageType) {
        Collection<ValidationEntry> matching = new LinkedList<ValidationEntry>();
        for (ValidationEntry validationValidationEntry : this.validations) {

            if (validationValidationEntry.messageType.equals(messageType)) {
                matching.add(validationValidationEntry);
            }
        }
        return matching;
    }

    // match subscriptions with existing validation entries
    // for each tuple of subscriber and message type the specified number of listeners must exist
    public void validate(SubscriptionManager subManager) {
        for (Class messageType : this.messageTypes) {
            Collection<ValidationEntry> validationEntries = getEntries(messageType);

            // we split subs + superSubs into TWO calls.
            Collection<Subscription> collection = new ArrayDeque<Subscription>(8);
            Subscription[] subscriptions = subManager.getSubs(messageType); // can return null
            if (subscriptions != null) {
                collection.addAll(Arrays.asList(subscriptions));
            }

            subscriptions = subManager.getSuperSubs(messageType); // NOT return null
            collection.addAll(Arrays.asList(subscriptions));

            assertEquals(validationEntries.size(), collection.size());


            for (ValidationEntry validationValidationEntry : validationEntries) {
                Subscription matchingSub = null;
                // one of the subscriptions must belong to the subscriber type
                for (Subscription sub : collection) {
                    if (belongsTo(sub, validationValidationEntry.subscriber)) {
                        matchingSub = sub;
                        break;
                    }
                }
                assertNotNull(matchingSub);
                assertEquals(this.subscribedListener.getNumberOfListeners(validationValidationEntry.subscriber), matchingSub.size());
            }
        }
    }


    /**
     * Check whether this subscription manages a message handler of the given message listener class
     */
    // only in unit test
    public boolean belongsTo(Subscription subscription, Class<?> listener) {
        return subscription.getListenerClass() == listener;
    }
}
