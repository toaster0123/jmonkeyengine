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

import com.jme3.texture.Image;
import org.jmonkeyengine.screenshottests.testframework.desktop.DesktopRunner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/** Reuses ScreenshotTest assertions and reporting with images captured out of process. */
public final class ComparePlatformCaptures {
    private ComparePlatformCaptures() {
    }

    public static void main(String[] args) throws IOException {
        String operation = args[0];
        Path captures = Path.of(args[1]);
        Map<String, Supplier<ScreenshotTest>> suite = ScreenshotSuite.all();
        if (suite.isEmpty()) {
            throw new IllegalStateException("No screenshot tests in shared suite");
        }
        List<String> manifest = new ArrayList<>();
        for (Map.Entry<String, Supplier<ScreenshotTest>> entry : suite.entrySet()) {
            String id = entry.getKey();
            if (!id.matches("[a-zA-Z0-9._-]+") || id.equals(".") || id.equals("..")) {
                throw new IllegalArgumentException("Invalid screenshot identifier: " + id);
            }
            int count = entry.getValue().get().scenarios.size();
            if (count == 0) {
                throw new IllegalArgumentException("Missing scenarios for " + id);
            }
            for (int i = 0; i < count; i++) {
                manifest.add(id + "\t" + i);
            }
        }
        if (operation.equals("list")) {
            Files.write(captures, manifest);
            return;
        }
        boolean generate = operation.equals("generate");
        if (!generate && !operation.equals("compare")) {
            throw new IllegalArgumentException("Unknown screenshot operation: " + operation);
        }
        Path baseline = Path.of(args[2]).toAbsolutePath();
        Path report = Path.of(args[3]).toAbsolutePath();
        Files.createDirectories(baseline);
        if (generate) {
            Files.deleteIfExists(baseline.resolve(ScreenshotTest.REFERENCE_RUN_COMPLETE_MARKER));
        }
        System.setProperty(ScreenshotTest.MODE_SYSTEM_PROPERTY, operation);
        System.setProperty(ScreenshotTest.REFERENCE_DIRECTORY_SYSTEM_PROPERTY, baseline.toString());

        int failed = 0;
        for (Map.Entry<String, Supplier<ScreenshotTest>> entry : suite.entrySet()) {
            String id = entry.getKey();
            TestReportCaptureBase.INSTANCE = new Report(report.resolve(id), id);
            try {
                ScreenshotTest test = entry.getValue().get().setBaseImageFileName(id);
                test.run(new CapturedRunner(captures.resolve(id), report.resolve("changed-images"), test.scenarios));
            } catch (RuntimeException | AssertionError error) {
                error.printStackTrace();
                failed++;
            } finally {
                TestReportCaptureBase.INSTANCE = null;
            }
        }
        if (failed != 0) {
            throw new IllegalStateException(failed + " screenshot test(s) failed; inspect the report and capture logs");
        }
        if (generate) {
            Files.writeString(baseline.resolve(ScreenshotTest.REFERENCE_RUN_COMPLETE_MARKER), "Reference complete");
        }
        System.out.println("Completed " + suite.size() + " tests / " + manifest.size() + " scenarios (" + operation + ")");
    }

    private static final class CapturedRunner extends DesktopRunner {
        private final Path input;
        private final Path changed;
        private final List<Scenario> scenarios;
        private int scenario;

        CapturedRunner(Path input, Path changed, List<Scenario> scenarios) {
            this.input = input;
            this.changed = changed;
            this.scenarios = scenarios;
        }

        @Override
        public void runApplicationUntilScenarioCompletes(TestContainingApp app, CountDownLatch finished) {
            TestDriver driver = app.getStateManager().getState(TestDriver.class);
            String scenarioName = scenarios.get(scenario).scenarioName;
            Path directory = input.resolve(Integer.toString(scenario++));
            try {
                int count = Integer.parseInt(Files.readString(directory.resolve("complete")).trim());
                if (count != driver.framesToTakeScreenshotsOn.size()) {
                    throw new IOException("Unexpected frame count in " + directory);
                }
                for (int frame : driver.framesToTakeScreenshotsOn) {
                    Path png = directory.resolve("f" + frame + ".png");
                    if (!Files.isRegularFile(png) || Files.size(png) == 0) {
                        throw new IOException("Missing or empty frame " + png);
                    }
                    driver.screenshotsAtFrames.recordScreenshot(scenarioName, frame, png);
                }
                finished.countDown();
            } catch (IOException error) {
                throw new UncheckedIOException("No complete screenshot capture: " + directory, error);
            }
        }

        @Override
        public Path getChangedImagesDirectory() {
            return changed;
        }

        @Override
        public <V> V fail(String message) {
            throw new AssertionError(message);
        }
    }

    private static final class Report extends TestReportCaptureBase {
        private final Path output;
        private final String id;

        Report(Path output, String id) {
            this.output = output;
            this.id = id;
        }

        @Override
        public void markFailInReport(String message) {
            System.err.println(id + ": " + message);
        }

        @Override
        public void warning(String message) {
            System.out.println(id + ": " + message);
        }

        @Override
        public void attachImageInner(String title, String fileName, Image image) {
            try {
                ScreenshotImageIo.writePng(image, output.resolve(fileName));
            } catch (IOException error) {
                throw new UncheckedIOException("Could not save report image " + fileName, error);
            }
        }
    }
}
