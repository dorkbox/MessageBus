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
package dorkbox.util.messagebus.subscription;

import dorkbox.util.messagebus.utils.VarArgUtils;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public
interface Subscriber {
    float LOAD_FACTOR = 0.8F;

    AtomicBoolean getVarArgPossibility();

    VarArgUtils getVarArgUtils();

    void register(Class<?> listenerClass, int handlersSize, Subscription[] subsPerListener);

    void shutdown();

    void clear();

    ArrayList<Subscription> getExactAsArray(Class<?> superClass);

    ArrayList<Subscription> getExactAsArray(Class<?> superClass1, Class<?> superClass2);

    ArrayList<Subscription> getExactAsArray(Class<?> superClass1, Class<?> superClass2, Class<?> superClass3);


    Subscription[] getExact(Class<?> deadMessageClass);

    Subscription[] getExact(Class<?> messageClass1, Class<?> messageClass2);

    Subscription[] getExact(Class<?> messageClass1, Class<?> messageClass2, Class<?> messageClass3);


    Subscription[] getExactAndSuper(Class<?> messageClass);

    Subscription[] getExactAndSuper(Class<?> messageClass1, Class<?> messageClass2);

    Subscription[] getExactAndSuper(Class<?> messageClass1, Class<?> messageClass2, Class<?> messageClass3);
}
