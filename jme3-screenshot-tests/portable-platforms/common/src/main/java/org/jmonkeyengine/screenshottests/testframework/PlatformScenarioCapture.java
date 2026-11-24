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

import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppState;
import com.jme3.math.FastMath;
import com.jme3.system.AppSettings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/** Runs one existing screenshot test scenario with the common test driver. */
final class PlatformScenarioCapture extends AbstractAppState {
    private final TestDriver driver;
    private final Path output;
    private final String scenarioName;
    private final List<Integer> frames;
    private final boolean stopWhenComplete;
    private TestContainingApp application;
    volatile boolean complete;

    private PlatformScenarioCapture(TestDriver driver, Path output, String scenarioName, List<Integer> frames,
            boolean stopWhenComplete) {
        this.driver = driver;
        this.output = output;
        this.scenarioName = scenarioName;
        this.frames = frames;
        this.stopWhenComplete = stopWhenComplete;
    }

    static TestContainingApp create(String id, int index, Path output, String renderer, boolean stopWhenComplete) {
        Supplier<ScreenshotTest> factory = ScreenshotSuite.all().get(id);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown screenshot test: " + id);
        }
        ScreenshotTest test = factory.get();
        Scenario scenario = test.scenarios.get(index);
        List<Integer> frames = new ArrayList<>(test.framesToTakeScreenshotsOn);
        FastMath.rand.setSeed(0);
        TestDriver driver = new TestDriver(scenario.scenarioName, frames);
        driver.waitLatch = new CountDownLatch(1);
        PlatformScenarioCapture capture = new PlatformScenarioCapture(
                driver, output, scenario.scenarioName, frames, stopWhenComplete);
        List<AppState> states = new ArrayList<>(Arrays.asList(scenario.states));
        states.add(driver);
        states.add(capture);
        TestContainingApp app = new TestContainingApp(states.toArray(new AppState[0])) {
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
        capture.application = app;
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(renderer);
        settings.setResolution(test.resolution.getWidth(), test.resolution.getHeight());
        settings.setDisplayScaleMode(test.displayScaleMode);
        settings.setAudioRenderer(null);
        settings.setUseInput(false);
        app.setSettings(settings);
        app.setShowSettings(false);
        return app;
    }

    @Override
    public void postRender() {
        if (complete || driver.waitLatch.getCount() != 0) {
            return;
        }
        try {
            Files.createDirectories(output);
            for (int frame : frames) {
                Path screenshot = driver.screenshotsAtFrames.getScreenshotsAtFrame(scenarioName, frame)
                        .orElseThrow(() -> new IOException("Missing screenshot at frame " + frame));
                if (Files.size(screenshot) == 0) {
                    throw new IOException("Empty screenshot at frame " + frame);
                }
                Files.copy(screenshot, output.resolve("f" + frame + ".png"));
                Files.deleteIfExists(screenshot);
            }
            Path marker = output.resolve("complete.tmp");
            Files.writeString(marker, Integer.toString(frames.size()));
            Files.move(marker, output.resolve("complete"), StandardCopyOption.ATOMIC_MOVE);
            complete = true;
            if (stopWhenComplete) {
                application.stop(false);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not save screenshot in " + output, exception);
        }
    }
}
