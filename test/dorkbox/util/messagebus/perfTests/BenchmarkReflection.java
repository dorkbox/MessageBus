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
package dorkbox.util.messagebus.perfTests;
import com.esotericsoftware.reflectasm.MethodAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;

// from: https://stackoverflow.com/questions/14146570/calling-a-getter-in-java-though-reflection-whats-the-fastest-way-to-repeatedly/14146919#14146919
// modified by dorkbox
public abstract class BenchmarkReflection {

    final String name;

    public BenchmarkReflection(String name) {
        this.name = name;
    }

    abstract int run(int iterations) throws Throwable;

    private
    BigDecimal time() {
        try {
            int nextI = 1;
            int i;
            long duration;

            do {
                i = nextI;
                long start = System.nanoTime();
                run(i);
                duration = System.nanoTime() - start;
                nextI = i << 1 | 1;
            } while (duration < 100000000 && nextI > 0);

            
            return new BigDecimal(duration * 1000 / i).movePointLeft(3);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return this.name + "\t" + time() + " ns";
    }

    static class C {
        public Integer foo() {
            return 1;
        }
    }

    static final MethodHandle sfmh;

    static {
        try {
            Method m = C.class.getMethod("foo");
            sfmh = MethodHandles.lookup().unreflect(m);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws Exception {
        final C invocationTarget = new C();
        final Method m_normal = C.class.getMethod("foo");

        final Method m_setAccessible = C.class.getMethod("foo");
        m_setAccessible.setAccessible(true);

        final MethodAccess m_asm = MethodAccess.get(C.class);
        final int mi = m_asm.getIndex("foo");

        final MethodHandle mh = sfmh;

        BenchmarkReflection[] marks = {
            new BenchmarkReflection("reflective invocation (without setAccessible)") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) m_normal.invoke(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("reflective invocation (with setAccessible)") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) m_setAccessible.invoke(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("reflectASM invocation") {

                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) m_asm.invoke(invocationTarget, mi, (Object)null);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("methodhandle (local) invocation") {

                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) mh.invokeExact(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("static final methodhandle invocation") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) sfmh.invokeExact(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("direct invocation") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += invocationTarget.foo();
                    }
                    return x;
                }
            },
        };
        for (BenchmarkReflection bm : marks) {
            System.out.println(bm);
        }
    }
}
