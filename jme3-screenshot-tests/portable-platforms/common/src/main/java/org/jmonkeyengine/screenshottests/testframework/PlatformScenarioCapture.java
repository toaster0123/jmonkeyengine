/*
 * Copyright (c) 2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.jmonkeyengine.screenshottests.testframework;

import com.jme3.app.Application;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.math.FastMath;
import com.jme3.system.AppSettings;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import org.jmonkeyengine.screenshottests.scenarios.gui.ScenarioBitmapText3D;
import org.jmonkeyengine.screenshottests.scenarios.material.ScenarioSimpleBumps;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Adapts existing shared screenshot scenarios to standalone desktop and native iOS processes. */
final class PlatformScenarioCapture extends AbstractAppState {
    private final List<Integer> frames;
    private final TestResolution resolution;
    private final Path output;
    private final boolean stopWhenComplete;
    private FrameBuffer buffer;
    private ByteBuffer pixels;
    private Application app;
    private int frame;
    private int count;
    volatile boolean complete;

    private PlatformScenarioCapture(ScreenshotTest test, Path output, boolean stopWhenComplete) {
        this.frames = new ArrayList<>(test.framesToTakeScreenshotsOn);
        this.resolution = test.resolution;
        this.output = output;
        this.stopWhenComplete = stopWhenComplete;
    }

    static TestContainingApp create(String name, Path output, String renderer, boolean stopWhenComplete) {
        ScreenshotTest test;
        switch (name) {
            case "bitmap-text":
                test = ScenarioBitmapText3D.testBitmapText3D();
                break;
            case "simple-bumps":
                test = ScenarioSimpleBumps.testSimpleBumps();
                break;
            default:
                throw new IllegalArgumentException("Unknown screenshot scenario: " + name);
        }
        if (test.scenarios.size() != 1 || test.framesToTakeScreenshotsOn.isEmpty()) {
            throw new IllegalArgumentException("Expected one scenario with at least one capture frame");
        }
        FastMath.rand.setSeed(0);
        List<AppState> states = new ArrayList<>(Arrays.asList(test.scenarios.get(0).states));
        PlatformScenarioCapture capture = new PlatformScenarioCapture(test, output, stopWhenComplete);
        states.add(capture);
        TestContainingApp result = new TestContainingApp(states.toArray(new AppState[0])) {
            @Override
            public void handleError(String message, Throwable cause) {
                IllegalStateException failure = new IllegalStateException(message, cause);
                failure.printStackTrace();
                onError.accept(failure);
                if (getContext() != null) {
                    stop(false);
                }
            }
        };
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(renderer);
        settings.setResolution(test.resolution.getWidth(), test.resolution.getHeight());
        settings.setDisplayScaleMode(test.displayScaleMode);
        settings.setAudioRenderer(null);
        settings.setUseInput(false);
        settings.setGammaCorrection(false);
        result.setSettings(settings);
        result.setShowSettings(false);
        return result;
    }

    @Override
    public void initialize(AppStateManager manager, Application application) {
        super.initialize(manager, application);
        app = application;
        int width = resolution.getWidth();
        int height = resolution.getHeight();
        Texture2D color = new Texture2D(width, height, Image.Format.RGBA8);
        color.setMinFilter(Texture.MinFilter.BilinearNearestMipMap);
        color.setMagFilter(Texture.MagFilter.Bilinear);
        color.getImage().setColorSpace(ColorSpace.sRGB);
        buffer = new FrameBuffer(width, height, 1);
        buffer.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        buffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(color));
        buffer.setSrgb(true);
        pixels = BufferUtils.createByteBuffer(width * height * 4);
        app.getRenderer().setMainFrameBufferOverride(buffer);
    }

    @Override
    public void postRender() {
        if (complete) {
            return;
        }
        int tick = frame++;
        if (!frames.contains(tick)) {
            return;
        }
        pixels.clear();
        app.getRenderer().readFrameBufferWithFormat(buffer, pixels, Image.Format.RGBA8);
        pixels.rewind();
        byte[] rgba = new byte[pixels.remaining()];
        pixels.get(rgba);
        try {
            Files.createDirectories(output);
            try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(
                    output.resolve("f" + tick + ".rgba")))) {
                stream.writeInt(buffer.getWidth());
                stream.writeInt(buffer.getHeight());
                stream.write(rgba);
            }
            if (++count == frames.size()) {
                Path marker = output.resolve("complete.tmp");
                Files.writeString(marker, Integer.toString(count));
                Files.move(marker, output.resolve("complete"), StandardCopyOption.ATOMIC_MOVE);
                complete = true;
                if (stopWhenComplete) {
                    app.stop(false);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save screenshot in " + output, exception);
        }
    }
}
