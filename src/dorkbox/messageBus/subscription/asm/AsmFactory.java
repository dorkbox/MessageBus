/*
 * Copyright 2016 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.messageBus.subscription.asm;

import dorkbox.messageBus.common.MessageHandler;
import dorkbox.messageBus.subscription.Subscription;
import dorkbox.messageBus.subscription.SubscriptionFactory;

/**
 * @author dorkbox, llc Date: 2/3/16
 */
public
class AsmFactory implements SubscriptionFactory {

    private final boolean useStrongReferencesByDefault;

    public
    AsmFactory(final boolean useStrongReferencesByDefault) {
        this.useStrongReferencesByDefault = useStrongReferencesByDefault;
    }

    @Override
    public
    Subscription<?> create(final Class<?> listenerClass, final MessageHandler handler) {
        // figure out what kind of references we want to use by default, as specified by MessageBus.useStrongReferencesByDefault
        final int referenceType = handler.getReferenceType();
        if (referenceType == MessageHandler.UNDEFINED) {
            if (useStrongReferencesByDefault) {
                return new SubscriptionAsmStrong(listenerClass, handler);
            }
            else {
                return new SubscriptionAsmWeak(listenerClass, handler);
            }
        }
        else if (referenceType == MessageHandler.WEAK) {
            return new SubscriptionAsmWeak(listenerClass, handler);
        }
        else {
            return new SubscriptionAsmStrong(listenerClass, handler);
        }
    }
}
