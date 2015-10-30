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
package dorkbox.util.messagebus;

public
class MTAQ_Accessor {

    MpmcMultiTransferArrayQueue mpmcMultiTransferArrayQueue;

    public
    MTAQ_Accessor(final int consumerCount) {
        mpmcMultiTransferArrayQueue = new MpmcMultiTransferArrayQueue(consumerCount);
    }

    public
    Object poll() {
        return mpmcMultiTransferArrayQueue.poll();
    }

    public
    boolean offer(final Object item) {
        return mpmcMultiTransferArrayQueue.offer(item);
    }

    public
    void take(final MultiNode node) throws InterruptedException {
        mpmcMultiTransferArrayQueue.take(node);
    }

    public
    void transfer(final Object item, final int type) throws InterruptedException {
        mpmcMultiTransferArrayQueue.transfer(item, type);
    }
}
