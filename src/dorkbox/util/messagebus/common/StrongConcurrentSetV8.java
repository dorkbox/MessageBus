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
package dorkbox.util.messagebus.common;

import java.util.concurrent.ConcurrentHashMap;

/**
 * This implementation uses strong references to the elements, uses an IdentityHashMap
 * <p/>
 *
 * @author dorkbox
 *         Date: 2/2/15
 */
public class StrongConcurrentSetV8<T> extends StrongConcurrentSet<T> {

    public StrongConcurrentSetV8(int size, float loadFactor) {
        // 1 for the stripe size, because that is the max concurrency with our concurrent set (since it uses R/W locks)
        super(new ConcurrentHashMap<T, ISetEntry<T>>(size, loadFactor, 16));
    }

    public StrongConcurrentSetV8(int size, float loadFactor, int stripeSize) {
        // 1 for the stripe size, because that is the max concurrency with our concurrent set (since it uses R/W locks)
        super(new ConcurrentHashMap<T, ISetEntry<T>>(size, loadFactor, stripeSize));
    }
}
