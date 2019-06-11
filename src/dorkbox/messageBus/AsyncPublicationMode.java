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
enum AsyncPublicationMode {

    /**
     * This is the default.
     * </p>
     * This uses the LMAX disruptor for managing the ASYNC publication of messages.
     * </p>
     * The Conversant Disruptor is shown to be faster, however the LMAX Disruptor DOES NOT generate any garbage when
     * running, while the Conversant Disruptor creates new `Runnable` for every invocation.
     */
    LmaxDisruptor,

    /**
     * This uses the Conversant disruptor + (many) runnable for managing the ASYNC publication of messages.
     * <p>
     * The Conversant Disruptor is shown to be faster than the LMAX disruptor, however the Conversant disruptor
     * relies on creating a `new Runnable()` for execution, where the LMAX Disruptor DOES NOT.
     */
    ConversantDisruptor,
}
