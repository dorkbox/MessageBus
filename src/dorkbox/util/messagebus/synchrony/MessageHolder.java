/*
 * Copyright 2015 dorkbox, llc
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
package dorkbox.util.messagebus.synchrony;

import dorkbox.util.messagebus.subscription.Subscription;
import dorkbox.util.messagebus.synchrony.disruptor.MessageType;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public
class MessageHolder {
    public int type = MessageType.ONE;
    public Subscription[] subscriptions;

    public Object message1 = null;
    public Object message2 = null;
    public Object message3 = null;

    public
    MessageHolder() {}

    public
    MessageHolder(Subscription[] subscriptions, Object message1) {
        this.subscriptions = subscriptions;
        this.message1 = message1;
    }

    public
    MessageHolder(Subscription[] subscriptions, Object message1, Object message2) {
        type = MessageType.TWO;

        this.subscriptions = subscriptions;
        this.message1 = message1;
        this.message2 = message2;
    }

    public
    MessageHolder(Subscription[] subscriptions, Object message1, Object message2, Object message3) {
        type = MessageType.THREE;

        this.subscriptions = subscriptions;
        this.message1 = message1;
        this.message2 = message2;
        this.message3 = message3;
    }
}
