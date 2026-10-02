/*
 * Validation fixture, distributed under the jMonkeyEngine BSD 3-Clause license.
 * See ../LICENSE for the copyright notice, conditions, and disclaimer.
 */
import com.jme3.renderer.opengl.GL3;
import com.jme3.renderer.opengl.GLExt;
import com.jme3.renderer.opengl.GLFbo;
import com.jme3.renderer.opengl.GLRenderer;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.util.Arrays;

/** Confirms invalidateState stays safe with no core-profile VAO bound. */
public class InvalidationCoreVaoSmoke extends DrawStatePixelSmoke {
    private static void coreContext() {
        display = fn("eglGetPlatformDisplayEXT").invokePointer(new Object[]{0x31dd, null, null});
        int[] major = {0};
        int[] minor = {0};
        need(EGL.getFunction("eglInitialize").invokeInt(new Object[]{display, major, minor}) != 0,
                "eglInitialize");
        need(EGL.getFunction("eglBindAPI").invokeInt(new Object[]{0x30a2}) != 0, "bind OpenGL");
        Memory configurations = new Memory(Native.POINTER_SIZE);
        int[] count = {0};
        int[] configAttributes = {0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8,
            0x3033, 1, 0x3040, 0x0008, 0x3038};
        need(EGL.getFunction("eglChooseConfig").invokeInt(new Object[]{display,
            configAttributes, configurations, 1, count}) != 0 && count[0] > 0, "OpenGL config");
        Pointer configuration = configurations.getPointer(0);
        surface = EGL.getFunction("eglCreatePbufferSurface").invokePointer(new Object[]{display,
            configuration, new int[]{0x3057, 4, 0x3056, 4, 0x3038}});
        int[] contextAttributes = {0x3098, 3, 0x30fb, 3, 0x30fd, 1, 0x3038};
        context = EGL.getFunction("eglCreateContext").invokePointer(new Object[]{display,
            configuration, null, contextAttributes});
        need(surface != null && context != null, "OpenGL core context");
        need(EGL.getFunction("eglMakeCurrent").invokeInt(new Object[]{display, surface,
            surface, context}) != 0, "make OpenGL current");
        need((integer(0x9126) & 1) != 0, "native core profile");
        System.out.println("DRIVER vendor=" + gs(0x1f00) + " renderer=" + gs(0x1f01)
                + " version=" + gs(0x1f02));
    }

    public static void main(String[] args) {
        coreContext();
        try {
            DrawBridge bridge = new DrawBridge();
            GLRenderer renderer = new GLRenderer(bridge.proxy(GL3.class),
                    bridge.proxy(GLExt.class), bridge.proxy(GLFbo.class));
            renderer.initialize();
            gl("glBindVertexArray", 0);
            clean("unbind VAO");
            int eventCount = EVENTS.size();
            renderer.invalidateState();
            need(EVENTS.size() == eventCount, "No divisor calls during invalidation");
            clean("invalidate with VAO 0");
            System.out.println("INVALIDATE vertexArray=" + integer(0x85b5) + " glErrors=0");
            gl("glVertexAttribDivisor", 4, 0);
            int[] actualErrors = errors();
            // Report the native control without assuming how this driver treats VAO 0.
            System.out.println("DIRECT-DIVISOR-CONTROL vertexArray=" + integer(0x85b5)
                    + " glVertexAttribDivisorErrors=" + Arrays.toString(actualErrors));
            clean("final");
            System.out.println("PASS core invalidation compatibility control");
        } finally {
            EGL.getFunction("eglMakeCurrent").invokeInt(new Object[]{display, null, null, null});
            EGL.getFunction("eglTerminate").invokeInt(new Object[]{display});
        }
    }
}
