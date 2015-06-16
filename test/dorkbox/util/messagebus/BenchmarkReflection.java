package dorkbox.util.messagebus;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.math.BigDecimal;

import com.esotericsoftware.reflectasm.MethodAccess;

// from: https://stackoverflow.com/questions/14146570/calling-a-getter-in-java-though-reflection-whats-the-fastest-way-to-repeatedly/14146919#14146919
// modified by dorkbox
public abstract class BenchmarkReflection {

    final String name;

    public BenchmarkReflection(String name) {
        this.name = name;
    }

    abstract int run(int iterations) throws Throwable;

    private BigDecimal time() {
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
        final Method m = C.class.getMethod("foo");
        final Method am = C.class.getMethod("foo");
        am.setAccessible(true);
        final MethodHandle mh = sfmh;

        final MethodAccess ma = MethodAccess.get(C.class);
        final int mi = ma.getIndex("foo");

        BenchmarkReflection[] marks = {
            new BenchmarkReflection("reflective invocation (without setAccessible)") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) m.invoke(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("reflective invocation (with setAccessible)") {
                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) am.invoke(invocationTarget);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("reflectASM invocation") {

                @Override int run(int iterations) throws Throwable {
                    int x = 0;
                    for (int i = 0; i < iterations; i++) {
                        x += (Integer) ma.invoke(invocationTarget, mi, (Object)null);
                    }
                    return x;
                }
            },
            new BenchmarkReflection("methodhandle invocation") {

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