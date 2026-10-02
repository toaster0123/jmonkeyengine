/*
 * Validation fixture, distributed under the jMonkeyEngine BSD 3-Clause license.
 * See ../LICENSE for the copyright notice, conditions, and disclaimer.
 */
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.StencilOperation;
import com.jme3.material.RenderState.TestFunction;
import com.jme3.renderer.Caps;
import com.jme3.renderer.opengl.GLES_30;
import com.jme3.renderer.opengl.GLExt;
import com.jme3.renderer.opengl.GLFbo;
import com.jme3.renderer.opengl.GLRenderer;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.Shader;
import com.jme3.util.BufferUtils;
import com.sun.jna.Pointer;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual renderer state decisions followed by native state and RGBA readbacks. */
public class DrawStatePixelSmoke extends RendererPixelSmoke {
    static final int STENCIL = 0x0b90;
    static final int DIVISOR = 0x88fe;
    static final int[] BLUE = {0, 0, 255, 255};
    static final int[] RED = {255, 0, 0, 255};
    static final int[] GREEN = {0, 255, 0, 255};
    static final int[] YELLOW = {255, 255, 0, 255};
    static final int[][] COLORS = {RED, GREEN, BLUE, YELLOW};
    static final List<String> EVENTS = new ArrayList<>();
    static boolean baseline;
    static int cases;
    static int reads;
    static int draws;
    static int stencilProgram;
    static int instanceProgram;

    static class DrawBridge extends Bridge {
        @Override
        public Object invoke(Object proxy, Method method, Object[] input) {
            String name = method.getName();
            Object[] arguments = input == null ? new Object[0] : input.clone();
            if (name.equals("glBufferData") && arguments.length == 3) {
                if (arguments[1] instanceof Buffer) {
                    Buffer buffer = (Buffer) arguments[1];
                    int elementBytes = buffer instanceof ByteBuffer ? 1
                            : buffer instanceof ShortBuffer ? 2 : 4;
                    gl(name, arguments[0], (long) buffer.remaining() * elementBytes,
                            ptr(buffer), arguments[2]);
                } else {
                    gl(name, arguments[0], arguments[1], null, arguments[2]);
                }
                EVENTS.add("glBufferData target=" + arguments[0]);
                return null;
            }
            if (name.equals("glVertexAttribPointer")) {
                arguments[3] = (byte) ((Boolean) arguments[3] ? 1 : 0);
                arguments[5] = Pointer.createConstant((Long) arguments[5]);
                gl(name, arguments);
                EVENTS.add(name + " " + Arrays.toString(arguments));
                return null;
            }
            if (name.equals("glVertexAttribDivisorARB")) {
                gl("glVertexAttribDivisor", arguments);
                EVENTS.add(name + " " + Arrays.toString(arguments));
                return null;
            }
            if (name.equals("glDrawArraysInstancedARB")) {
                EVENTS.add("DRAW nativeDivisor=" + divisor(4) + " " + Arrays.toString(arguments));
                gl("glDrawArraysInstanced", arguments);
                draws++;
                return null;
            }
            if (name.equals("glStencilFuncSeparate") || name.equals("glStencilOpSeparate")
                    || ((name.equals("glEnable") || name.equals("glDisable"))
                    && (Integer) arguments[0] == STENCIL)) {
                EVENTS.add(name + " " + Arrays.toString(arguments));
            }
            return super.invoke(proxy, method, input);
        }
    }

    static int integer(int name) {
        int[] result = {0};
        gl("glGetIntegerv", name, result);
        return result[0];
    }

    static int divisor(int slot) {
        int[] result = {0};
        gl("glGetVertexAttribiv", slot, DIVISOR, result);
        return result[0];
    }

    static boolean enabled() {
        return ((Byte) call("glIsEnabled", Byte.TYPE, STENCIL)) != 0;
    }

    /** Isolates cases by resetting the native state before making a new renderer. */
    static GLRenderer rendererFresh() {
        gl("glDisable", STENCIL);
        gl("glDisable", 0x0b71); // depth test
        gl("glDisable", 0x0be2); // blend
        gl("glDisable", 0x0b44); // cull face
        gl("glDisable", 0x0c11); // scissor
        gl("glDisable", 0x0bd0); // dither
        gl("glColorMask", (byte) 1, (byte) 1, (byte) 1, (byte) 1);
        gl("glDepthMask", (byte) 1);
        for (int slot = 0; slot < 16; slot++) {
            gl("glDisableVertexAttribArray", slot);
            gl("glVertexAttribDivisor", slot, 0);
        }
        DrawBridge bridge = new DrawBridge();
        GLRenderer renderer = new GLRenderer(bridge.proxy(GLES_30.class),
                bridge.proxy(GLExt.class), bridge.proxy(GLFbo.class));
        renderer.initialize();
        need(renderer.getCaps().contains(Caps.MeshInstancing), "native instancing capability");
        clean("renderer initialization");
        return renderer;
    }

    static void framebufferSetup() {
        int texture = gen("Textures");
        gl("glBindTexture", TEX2D, texture);
        gl("glTexImage2D", TEX2D, 0, 0x8058, 32, 8, 0, RGBA, UBYTE, null); // RGBA8
        int renderbuffer = gen("Renderbuffers");
        gl("glBindRenderbuffer", 0x8d41, renderbuffer);
        gl("glRenderbufferStorage", 0x8d41, 0x88f0, 32, 8); // D24S8
        framebuffer = gen("Framebuffers");
        gl("glBindFramebuffer", 0x8d40, framebuffer);
        gl("glFramebufferTexture2D", 0x8d40, 0x8ce0, TEX2D, texture, 0);
        gl("glFramebufferRenderbuffer", 0x8d40, 0x821a, 0x8d41, renderbuffer);
        need(gi("glCheckFramebufferStatus", 0x8d40) == 0x8cd5, "RGBA8 + D24S8 FBO complete");
        gl("glViewport", 0, 0, 32, 8);
        need(integer(0x0d57) == 8, "8 native stencil bits");
        clean("framebuffer setup");
    }

    static int link(String vertex, String fragment) {
        int program = gi("glCreateProgram");
        gl("glAttachShader", program, shader(0x8b31, vertex));
        gl("glAttachShader", program, shader(0x8b30, fragment));
        gl("glLinkProgram", program);
        int[] success = {0};
        gl("glGetProgramiv", program, 0x8b82, success);
        need(success[0] != 0, "program link");
        return program;
    }

    static void programs() {
        // One full-screen triangle. Flipping x selects its front or back face.
        stencilProgram = link("""
                #version 300 es
                uniform float flip;
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2) * 2.0 - 1.0;
                    gl_Position = vec4(p.x * flip, p.y, 0, 1);
                }
                """, """
                #version 300 es
                precision highp float;
                out vec4 color;
                void main() { color = vec4(1, 0, 0, 1); }
                """);
        // Four disjoint columns; attribute slot 4 determines their flat colors.
        instanceProgram = link("""
                #version 300 es
                layout(location = 0) in vec3 inPosition;
                layout(location = 4) in vec4 inTexCoord2;
                flat out vec4 chosen;
                void main() {
                    gl_Position = vec4(inPosition.x * 0.25 - 0.75
                        + float(gl_InstanceID) * 0.5, inPosition.y, 0, 1);
                    chosen = inTexCoord2;
                }
                """, """
                #version 300 es
                precision highp float;
                flat in vec4 chosen;
                out vec4 color;
                void main() { color = chosen; }
                """);
        clean("program setup");
    }

    static RenderState stencil(boolean test, TestFunction function) {
        RenderState state = new RenderState();
        state.setDepthTest(false);
        state.setDepthWrite(false);
        state.setFaceCullMode(RenderState.FaceCullMode.Off);
        state.setStencil(test, StencilOperation.Keep, StencilOperation.Keep,
                StencilOperation.Keep, StencilOperation.Keep, StencilOperation.Keep,
                StencilOperation.Keep, function, function);
        return state;
    }

    static void clearBlue() {
        gl("glClearColor", 0f, 0f, 1f, 1f);
        gl("glStencilMask", 255);
        gl("glClearStencil", 2);
        gl("glClear", 0x4000 | 0x0400);
        clean("clear blue/stencil2");
    }

    static int[] read(int x, int y) {
        byte[] bytes = new byte[4];
        gl("glReadPixels", x, y, 1, 1, RGBA, UBYTE, bytes);
        reads++;
        return new int[]{bytes[0] & 255, bytes[1] & 255, bytes[2] & 255, bytes[3] & 255};
    }

    static int[] stencilDraw(boolean front) {
        gl("glUseProgram", stencilProgram);
        gl("glUniform1f", gi("glGetUniformLocation", stencilProgram, "flip"), front ? 1f : -1f);
        gl("glDrawArrays", 4, 0, 3);
        draws++;
        int[] pixel = read(16, 4);
        clean("stencil draw/read");
        return pixel;
    }

    static void stencilCheck(String label, boolean expectedEnabled, int[] expectedPixel,
            boolean front) {
        int[] actual = stencilDraw(front);
        boolean actualEnabled = enabled();
        clean(label);
        System.out.println("STENCIL phase=" + label + " enabled=" + actualEnabled
                + " pixel=" + Arrays.toString(actual) + " expectedEnabled=" + expectedEnabled
                + " expectedPixel=" + Arrays.toString(expectedPixel) + " errors=[]");
        need(actualEnabled == expectedEnabled && Arrays.equals(actual, expectedPixel), label);
    }

    static void stencilDisable(boolean changeFunction) {
        GLRenderer renderer = rendererFresh();
        String name = changeFunction ? "disable-changing-function-control"
                : "disable-unchanged-parameters";
        EVENTS.add("CASE " + name);
        clearBlue();
        renderer.applyRenderState(stencil(true, TestFunction.Never));
        stencilCheck(name + "/enabled-Never", true, BLUE, true);
        renderer.applyRenderState(stencil(false,
                changeFunction ? TestFunction.Always : TestFunction.Never));
        boolean expectedBug = baseline && !changeFunction;
        stencilCheck(name + "/disabled", expectedBug, expectedBug ? BLUE : RED, true);
        cases++;
    }

    static void stencilReference(boolean front) {
        GLRenderer renderer = rendererFresh();
        String name = (front ? "front" : "back") + "-reference-only-control";
        EVENTS.add("CASE " + name);
        RenderState state = stencil(true, TestFunction.Equal);
        state.setFrontStencilReference(1);
        state.setBackStencilReference(1);
        clearBlue();
        renderer.applyRenderState(state);
        stencilCheck(name + "/ref1", true, BLUE, front);
        if (front) {
            state.setFrontStencilReference(2);
        } else {
            state.setBackStencilReference(2);
        }
        renderer.applyRenderState(state);
        stencilCheck(name + "/ref2", true, RED, front);
        cases++;
    }

    static void stencilMask(boolean front) {
        GLRenderer renderer = rendererFresh();
        String name = (front ? "front" : "back") + "-mask-only-control";
        EVENTS.add("CASE " + name);
        RenderState state = stencil(true, TestFunction.Equal);
        state.setFrontStencilReference(0);
        state.setBackStencilReference(0);
        state.setFrontStencilMask(1);
        state.setBackStencilMask(1);
        clearBlue();
        renderer.applyRenderState(state);
        stencilCheck(name + "/mask1", true, RED, front);
        if (front) {
            state.setFrontStencilMask(2);
        } else {
            state.setBackStencilMask(2);
        }
        clearBlue();
        renderer.applyRenderState(state);
        stencilCheck(name + "/mask2", true, BLUE, front);
        cases++;
    }

    static VertexBuffer colors(int span, boolean constant) {
        float[] data = new float[24];
        for (int vertex = 0; vertex < 6; vertex++) {
            int[] color = constant ? RED : COLORS[vertex % 4];
            for (int component = 0; component < 4; component++) {
                data[vertex * 4 + component] = color[component] / 255f;
            }
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Type.TexCoord2);
        buffer.setupData(VertexBuffer.Usage.Static, 4, VertexBuffer.Format.Float,
                BufferUtils.createFloatBuffer(data));
        buffer.setInstanceSpan(span);
        return buffer;
    }

    static Mesh mesh() {
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, new float[]{
            -1, -1, 0, 1, -1, 0, -1, 1, 0,
            -1, 1, 0, 1, -1, 0, 1, 1, 0
        });
        return mesh;
    }

    static Shader meshShader() {
        Shader shader = new Shader();
        shader.setId(instanceProgram);
        shader.clearUpdateNeeded();
        shader.getAttribute(VertexBuffer.Type.Position).setLocation(0);
        shader.getAttribute(VertexBuffer.Type.TexCoord2).setLocation(4);
        return shader;
    }

    static void instanceDraw(GLRenderer renderer, Mesh mesh, VertexBuffer buffer, String phase,
            int expectedDivisor, boolean constant) {
        clearBlue();
        renderer.renderMesh(mesh, 0, 4, new VertexBuffer[]{buffer});
        int actualDivisor = divisor(4);
        List<String> actualPixels = new ArrayList<>();
        List<String> expectedPixels = new ArrayList<>();
        for (int instance = 0; instance < 4; instance++) {
            int[] actual = read(instance * 8 + 4, 4);
            int[] expected = constant ? RED : COLORS[instance / expectedDivisor];
            actualPixels.add(Arrays.toString(actual));
            expectedPixels.add(Arrays.toString(expected));
            need(Arrays.equals(actual, expected), phase + " pixel column " + instance
                    + " got " + Arrays.toString(actual) + " expected " + Arrays.toString(expected));
        }
        clean(phase);
        System.out.println("DIVISOR phase=" + phase + " requestedSpan=" + buffer.getInstanceSpan()
                + " nativeDivisor=" + actualDivisor + " expectedNative=" + expectedDivisor
                + " pixels=" + actualPixels + " expectedPixels=" + expectedPixels + " errors=[]");
        need(actualDivisor == expectedDivisor, phase + " native divisor");
    }

    static void instanceTransition(int firstSpan, int secondSpan, boolean sameBuffer) {
        GLRenderer renderer = rendererFresh();
        renderer.applyRenderState(stencil(false, TestFunction.Always));
        renderer.setShader(meshShader());
        Mesh mesh = mesh();
        boolean constant = firstSpan == 0 || secondSpan == 0;
        VertexBuffer first = colors(firstSpan, constant);
        VertexBuffer second = sameBuffer ? first : colors(secondSpan, constant);
        String label = "span-" + firstSpan + "-to-" + secondSpan + "/"
                + (sameBuffer ? "same-buffer" : "different-buffer");
        EVENTS.add("CASE " + label);
        instanceDraw(renderer, mesh, first, label + "/first", firstSpan, constant);
        if (sameBuffer) {
            second.setInstanceSpan(secondSpan);
        }
        int expected = baseline && firstSpan > 0 && secondSpan > 0 ? firstSpan : secondSpan;
        instanceDraw(renderer, mesh, second, label + "/second", expected, constant);
        cases++;
    }

    public static void main(String[] arguments) throws Exception {
        String mode = arguments[0];
        baseline = arguments[1].equals("baseline");
        egl();
        try {
            framebufferSetup();
            programs();
            if (mode.equals("stencil")) {
                stencilDisable(false);
                stencilDisable(true);
                stencilReference(true);
                stencilReference(false);
                stencilMask(true);
                stencilMask(false);
            } else if (mode.equals("divisor")) {
                instanceTransition(1, 2, false);
                instanceTransition(2, 1, false);
                instanceTransition(1, 2, true);
                instanceTransition(2, 1, true);
                instanceTransition(0, 2, false);
                instanceTransition(2, 0, false);
            } else {
                throw new IllegalArgumentException("Unknown mode " + mode);
            }
            clean("final");
            Files.write(Path.of(arguments[2]), EVENTS);
            System.out.println("PASS mode=" + mode + " version=" + arguments[1]
                    + " cases=" + cases + " nativeDraws=" + draws
                    + " pixelReadbacks=" + reads + " glErrors=0");
        } finally {
            EGL.getFunction("eglMakeCurrent").invokeInt(new Object[]{display, null, null, null});
            EGL.getFunction("eglTerminate").invokeInt(new Object[]{display});
        }
    }
}
