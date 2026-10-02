/*
 * Validation fixture, distributed under the jMonkeyEngine BSD 3-Clause license.
 * See ../LICENSE for the copyright notice, conditions, and disclaimer.
 */
import com.jme3.renderer.opengl.GLFbo;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal native test backend for the draw-state probes.
 *
 * Every GL operation is forwarded through installed JNA to the current native
 * context. This does not exercise the production LWJGL or Android backend.
 * Resources are process-local; each probe runs in a separate short-lived JVM.
 */
public class RendererPixelSmoke {
    static final int TEX2D = 0x0de1;
    static final int RGBA = 0x1908;
    static final int UBYTE = 0x1401;
    static final NativeLibrary EGL = NativeLibrary.getInstance("EGL");
    static final Map<String, Function> FUNCTIONS = new HashMap<>();
    static Pointer display;
    static Pointer surface;
    static Pointer context;
    static int framebuffer;

    static Function fn(String name) {
        return FUNCTIONS.computeIfAbsent(name, key -> {
            Pointer address = EGL.getFunction("eglGetProcAddress")
                    .invokePointer(new Object[]{key});
            need(address != null, "Missing native function " + key);
            return Function.getFunction(address);
        });
    }

    static Object call(String name, Class<?> returnType, Object... arguments) {
        return fn(name).invoke(returnType, arguments);
    }

    static void gl(String name, Object... arguments) {
        call(name, Void.TYPE, arguments);
    }

    static int gi(String name, Object... arguments) {
        return (Integer) call(name, Integer.TYPE, arguments);
    }

    static String gs(int parameter) {
        return ((Pointer) call("glGetString", Pointer.class, parameter)).getString(0);
    }

    static void need(boolean condition, String explanation) {
        if (!condition) {
            throw new AssertionError(explanation);
        }
    }

    static int[] errors() {
        ArrayList<Integer> found = new ArrayList<>();
        int error;
        while ((error = gi("glGetError")) != 0) {
            found.add(error);
        }
        return found.stream().mapToInt(value -> value).toArray();
    }

    static void clean(String where) {
        int[] found = errors();
        need(found.length == 0, "GL errors at " + where + ": "
                + java.util.Arrays.toString(found));
    }

    /** Creates a surfaceless Mesa EGL display, pbuffer, and GLES 3 context. */
    static void egl() {
        display = fn("eglGetPlatformDisplayEXT")
                .invokePointer(new Object[]{0x31dd, null, null});
        int[] major = {0};
        int[] minor = {0};
        need(EGL.getFunction("eglInitialize")
                .invokeInt(new Object[]{display, major, minor}) != 0, "eglInitialize");
        need(EGL.getFunction("eglBindAPI")
                .invokeInt(new Object[]{0x30a0}) != 0, "eglBindAPI GLES");
        Memory configurations = new Memory(Native.POINTER_SIZE);
        int[] count = {0};
        int[] attributes = {
            0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, // RGBA8
            0x3033, 1,                                  // pbuffer
            0x3040, 0x40,                               // GLES 3
            0x3038                                      // EGL_NONE
        };
        need(EGL.getFunction("eglChooseConfig").invokeInt(new Object[]{display,
            attributes, configurations, 1, count}) != 0 && count[0] > 0, "EGL config");
        Pointer configuration = configurations.getPointer(0);
        surface = EGL.getFunction("eglCreatePbufferSurface").invokePointer(new Object[]{
            display, configuration, new int[]{0x3057, 4, 0x3056, 4, 0x3038}});
        context = EGL.getFunction("eglCreateContext").invokePointer(new Object[]{
            display, configuration, null, new int[]{0x3098, 3, 0x3038}});
        need(surface != null && context != null, "EGL pbuffer/context");
        need(EGL.getFunction("eglMakeCurrent")
                .invokeInt(new Object[]{display, surface, surface, context}) != 0,
                "EGL make current");
        System.out.println("DRIVER vendor=" + gs(0x1f00) + " renderer=" + gs(0x1f01)
                + " version=" + gs(0x1f02) + " GLSL=" + gs(0x8b8c));
    }

    static Pointer ptr(Buffer buffer) {
        int elementBytes = buffer instanceof ByteBuffer ? 1
                : buffer instanceof ShortBuffer ? 2
                : buffer instanceof LongBuffer || buffer instanceof DoubleBuffer ? 8 : 4;
        return Native.getDirectBufferPointer(buffer)
                .share((long) buffer.position() * elementBytes);
    }

    /** Adapts only the native signatures exercised by these short probes. */
    static class Bridge implements InvocationHandler {
        @SuppressWarnings("unchecked")
        <T> T proxy(Class<T> type) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] input) {
            String name = method.getName();
            if (name.equals("supportsGpuTimerQuery")) {
                return false;
            }
            Object[] arguments = input == null ? new Object[0] : input.clone();
            if (name.equals("glGetString")) {
                Pointer result = (Pointer) call(arguments.length == 2 ? "glGetStringi" : name,
                        Pointer.class, arguments);
                return result == null ? null : result.getString(0);
            }
            if (name.equals("glGetInteger")) {
                name = "glGetIntegerv";
            } else if (name.equals("glGetFloat")) {
                name = "glGetFloatv";
            } else if (name.equals("glGetBoolean")) {
                name = "glGetBooleanv";
            }
            if (name.endsWith("EXT") && method.getDeclaringClass() == GLFbo.class) {
                name = name.substring(0, name.length() - 3);
            }
            if ((name.startsWith("glGen") || name.startsWith("glDelete"))
                    && arguments.length == 1 && arguments[0] instanceof Buffer) {
                arguments = new Object[]{((Buffer) arguments[0]).remaining(), arguments[0]};
            }
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] instanceof Buffer) {
                    arguments[i] = ptr((Buffer) arguments[i]);
                } else if (arguments[i] instanceof Boolean) {
                    arguments[i] = (byte) ((Boolean) arguments[i] ? 1 : 0);
                }
            }
            if (method.getReturnType() == boolean.class) {
                return ((Byte) call(name, Byte.TYPE, arguments)) != 0;
            }
            return call(name, method.getReturnType(), arguments);
        }
    }

    static int shader(int type, String source) {
        int id = gi("glCreateShader", type);
        Memory bytes = new Memory(source.length() + 1);
        bytes.setString(0, source);
        Memory strings = new Memory(Native.POINTER_SIZE);
        strings.setPointer(0, bytes);
        gl("glShaderSource", id, 1, strings, null);
        gl("glCompileShader", id);
        int[] success = {0};
        gl("glGetShaderiv", id, 0x8b81, success);
        if (success[0] == 0) {
            byte[] log = new byte[4096];
            gl("glGetShaderInfoLog", id, 4096, null, log);
            throw new AssertionError(new String(log));
        }
        return id;
    }

    static int gen(String kind) {
        int[] id = {0};
        gl("glGen" + kind, 1, id);
        return id[0];
    }
}
