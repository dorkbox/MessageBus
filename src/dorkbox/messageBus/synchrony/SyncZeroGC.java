/*
 * Copyright 2019 dorkbox, llc
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
package dorkbox.messageBus.synchrony;

import org.vibur.objectpool.PoolService;

import dorkbox.messageBus.dispatch.Dispatch;


/**
 * @author dorkbox, llc Date: 2/2/15
 */
@SuppressWarnings("Duplicates")
public final
class SyncZeroGC implements SynchronyZeroGC {

    private final Dispatch dispatch;

    public
    SyncZeroGC(final Dispatch dispatch) {
        this.dispatch = dispatch;
    }

    @Override
    public
    <T> void publish(final PoolService<T> pool, final T message) {
        try {
            dispatch.publish(message);
        } finally {
            pool.restore(message);
        }
    }

    @Override
    public
    <T1, T2> void publish(final PoolService<T1> pool1, final PoolService<T2> pool2,
                          final T1 message1, final T2 message2) {
        try {
            dispatch.publish(message1, message2);
        } finally {
            pool1.restore(message1);
            pool2.restore(message2);
        }
    }

    @Override
    public
    <T1, T2, T3> void publish(final PoolService<T1> pool1, final PoolService<T2> pool2, final PoolService<T3> pool3,
                              final T1 message1, final T2 message2, final T3 message3) {
        try {
            dispatch.publish(message1, message2, message3);
        } finally {
            pool1.restore(message1);
            pool2.restore(message2);
            pool3.restore(message3);
        }
    }

    @Override
    public
    void shutdown() {
    }

    @Override
    public
    boolean hasPendingMessages() {
        return false;
    }
}
