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

/**
 * @author dorkbox, llc Date: 2/3/16
 */
public
interface SynchronyZeroGC {
    <T> void publish(PoolService<T> pool, T message1);

    <T1, T2> void publish(PoolService<T1> pool1, PoolService<T2> pool2,
                          T1 message1, T2 message2);

    <T1, T2, T3> void publish(PoolService<T1> pool1, PoolService<T2> pool2, PoolService<T3> pool3,
                              T1 message1, T2 message2, T3 message3);

    void shutdown();

    boolean hasPendingMessages();
}
