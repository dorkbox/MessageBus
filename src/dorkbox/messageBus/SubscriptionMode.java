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
package dorkbox.messageBus;

public
enum SubscriptionMode {
    /**
     * This is the default.
     * <p>
     * We use strong references when saving the subscribed listeners (these are the classes & methods that receive messages).
     * <p>
     * In certain environments (ie: spring), it is desirable to use weak references -- so that there are no memory leaks during
     * the container lifecycle (or, more specifically, so one doesn't have to manually manage the memory).
     */
    StrongReferences,

    /**
     * Using weak references is a tad slower than using strong references, since there are additional steps taken when there are orphaned
     * references (when GC occurs) that have to be cleaned up. This cleanup occurs during message publication.
     */
    WeakReferences
}
