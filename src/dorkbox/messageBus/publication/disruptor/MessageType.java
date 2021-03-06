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
package dorkbox.messageBus.publication.disruptor;

/**
 * @author dorkbox, llc Date: 2/2/15
 */
public final class MessageType {
    public static final int ASM_ONE = 1;
    public static final int REFLECT_ONE = 2;

    public static final int ASM_TWO = 3;
    public static final int REFLECT_TWO = 4;

    public static final int ASM_THREE = 5;
    public static final int REFLECT_THREE = 6;

    private MessageType() {
    }
}
