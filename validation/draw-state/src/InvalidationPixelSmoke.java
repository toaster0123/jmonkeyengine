/*
 * Validation fixture, distributed under the jMonkeyEngine BSD 3-Clause license.
 * See ../LICENSE for the copyright notice, conditions, and disclaimer.
 */
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.TestFunction;
import com.jme3.renderer.opengl.GLRenderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.util.BufferUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native invalidation regression probe; inherited EGL bridge invokes installed Mesa. */
public class InvalidationPixelSmoke extends DrawStatePixelSmoke {
    private static boolean repaired;
    private static boolean stencilCacheFix;

    private static VertexBuffer vertexColors(int span) {
        // Both triangles' provoking vertices (2 and 5) are red with divisor 0.
        // With divisor 2, instances 0/1 read red and instances 2/3 read green.
        int[][] entries = {RED, GREEN, RED, RED, RED, RED};
        float[] data = new float[24];
        for (int i = 0; i < entries.length; i++) {
            for (int j = 0; j < 4; j++) {
                data[i * 4 + j] = entries[i][j] / 255f;
            }
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Type.TexCoord2);
        buffer.setupData(VertexBuffer.Usage.Static, 4, VertexBuffer.Format.Float,
                BufferUtils.createFloatBuffer(data));
        buffer.setInstanceSpan(span);
        return buffer;
    }

    private static void draw(GLRenderer renderer, Mesh mesh, VertexBuffer buffer,
            String label, int expectedNativeDivisor) {
        clearBlue();
        renderer.renderMesh(mesh, 0, 4, new VertexBuffer[]{buffer});
        int actualDivisor = divisor(4);
        List<String> actualPixels = new ArrayList<>();
        List<String> expectedPixels = new ArrayList<>();
        for (int instance = 0; instance < 4; instance++) {
            int[] actual = read(instance * 8 + 6, 2);
            int[] expected = expectedNativeDivisor == 0 ? RED
                    : (instance / expectedNativeDivisor == 1 ? GREEN : RED);
            actualPixels.add(Arrays.toString(actual));
            expectedPixels.add(Arrays.toString(expected));
            need(Arrays.equals(actual, expected), label + " instance=" + instance);
        }
        clean(label);
        System.out.println("DIVISOR label=" + label + " requested=" + buffer.getInstanceSpan()
                + " native=" + actualDivisor + " expectedNative=" + expectedNativeDivisor
                + " actualPixels=" + actualPixels + " expectedPixels=" + expectedPixels
                + " glErrors=0");
        need(actualDivisor == expectedNativeDivisor, label + " native divisor");
    }

    private static void divisorCase(String name, boolean external, boolean previouslyUsed,
            boolean invalidate, int requested) {
        GLRenderer renderer = rendererFresh();
        RenderState renderState = stencil(false, TestFunction.Always);
        renderer.applyRenderState(renderState);
        Shader shader = meshShader();
        renderer.setShader(shader);
        Mesh mesh = mesh();
        EVENTS.add("CASE " + name);
        if (previouslyUsed) {
            VertexBuffer previous = vertexColors(external ? 0 : 2);
            draw(renderer, mesh, previous, name + "/before", previous.getInstanceSpan());
        }
        if (external) {
            gl("glVertexAttribDivisor", 4, 2);
            need(divisor(4) == 2, "external divisor changed");
            EVENTS.add("EXTERNAL glVertexAttribDivisor [4, 2]");
        }
        if (invalidate) {
            renderer.invalidateState();
            EVENTS.add("INVALIDATE nativeDivisor=" + divisor(4));
            renderer.applyRenderState(renderState);
            renderer.setShader(shader);
        }
        int expected = !repaired && invalidate && requested == 0 ? 2 : requested;
        draw(renderer, mesh, vertexColors(requested), name + "/after", expected);
        cases++;
    }

    private static void stencilCase(String name, boolean external, boolean changeFunction, boolean nativeMask) {
        GLRenderer renderer = rendererFresh();
        EVENTS.add("CASE " + name);
        clearBlue();
        if (external) {
            gl("glEnable", STENCIL);
            gl("glStencilFuncSeparate", 0x0404, 0x0200, 0, -1);
            gl("glStencilFuncSeparate", 0x0405, 0x0200, 0, -1);
            EVENTS.add("EXTERNAL enabled stencil Never");
        } else {
            renderer.applyRenderState(stencil(true, TestFunction.Never));
        }
        stencilCheck(name + "/before", true, BLUE, true);
        renderer.invalidateState();
        EVENTS.add("INVALIDATE nativeStencil=" + enabled());
        RenderState requested = stencil(false,
                changeFunction ? TestFunction.Never : TestFunction.Always);
        if (nativeMask) {
            requested.setFrontStencilMask(-1);
            requested.setBackStencilMask(-1);
        }
        renderer.applyRenderState(requested);
        boolean expectedEnabled = !repaired && !changeFunction && (!stencilCacheFix || nativeMask);
        stencilCheck(name + "/after", expectedEnabled, expectedEnabled ? BLUE : RED, true);
        cases++;
    }

    public static void main(String[] args) throws Exception {
        repaired = args[1].equals("repaired");
        stencilCacheFix = args[1].equals("pr2987");
        egl();
        try {
            framebufferSetup();
            programs();
            if (args[0].equals("divisor")) {
                divisorCase("renderer-span2-invalidate-span0", false, true, true, 0);
                divisorCase("external-used-slot-span2-invalidate-span0", true, true, true, 0);
                divisorCase("external-unused-slot-span2-invalidate-span0", true, false, true, 0);
                divisorCase("renderer-span2-no-invalidate-span0-control", false, true, false, 0);
                divisorCase("external-span2-invalidate-span1-control", true, true, true, 1);
                divisorCase("external-span2-invalidate-span2-control", true, false, true, 2);
            } else {
                stencilCase("renderer-enabled-invalidate-disabled-defaults", false, false, false);
                stencilCase("external-enabled-invalidate-disabled-defaults", true, false, false);
                stencilCase("external-enabled-invalidate-disabled-native-mask", true, false, true);
                stencilCase("external-enabled-invalidate-disabled-changed-function-control", true, true, true);
            }
            clean("final");
            Files.write(Path.of(args[2]), EVENTS);
            System.out.println("PASS mode=" + args[0] + " version=" + args[1]
                    + " cases=" + cases + " nativeDraws=" + draws
                    + " pixelReadbacks=" + reads + " glErrors=0");
        } finally {
            EGL.getFunction("eglMakeCurrent").invokeInt(new Object[]{display, null, null, null});
            EGL.getFunction("eglTerminate").invokeInt(new Object[]{display});
        }
    }
}
