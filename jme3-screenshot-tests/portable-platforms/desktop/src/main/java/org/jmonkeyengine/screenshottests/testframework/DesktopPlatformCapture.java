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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the desktop context on the JVM's first thread, as macOS SDL requires. */
public final class DesktopPlatformCapture {
    private static final String TIMEOUT_PROPERTY = "jme.screenshot.captureTimeoutSeconds";
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 180;
    private static final int SHUTDOWN_GRACE_SECONDS = 15;

    private DesktopPlatformCapture() {
    }

    public static void main(String[] args) throws Exception {
        int timeoutSeconds = captureTimeoutSeconds();
        Path output = Path.of(args[2]).toAbsolutePath();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Expected an unused output directory: " + output);
        }
        TestContainingApp app = PlatformScenarioCapture.create(args[0], Integer.parseInt(args[1]), output, args[3], true);
        PlatformScenarioCapture capture = app.getStateManager().getState(PlatformScenarioCapture.class);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        app.onError = failure::set;
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds - SHUTDOWN_GRACE_SECONDS));
        Thread launcherThread = Thread.currentThread();
        CountDownLatch finished = new CountDownLatch(1);
        Thread watchdog = new Thread(() -> {
            try {
                if (!finished.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    try {
                        System.err.printf("Screenshot process timed out after %d s (test %s/%s; capture complete: %s)%n",
                                timeoutSeconds, args[0], args[1], capture.complete);
                        System.err.printf("Screenshot launcher thread %s (%s)%n",
                                launcherThread.getName(), launcherThread.getState());
                        for (StackTraceElement frame : launcherThread.getStackTrace()) {
                            System.err.println("    at " + frame);
                        }
                    } finally {
                        System.exit(1);
                    }
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }, "screenshot-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            app.start();
            waitForCapture(app, capture, failure, deadline);
        } finally {
            finished.countDown();
        }
    }

    private static int captureTimeoutSeconds() {
        String configured = System.getProperty(TIMEOUT_PROPERTY, Integer.toString(DEFAULT_TIMEOUT_SECONDS));
        try {
            int seconds = Integer.parseInt(configured);
            if (seconds >= 2 && seconds <= MAX_TIMEOUT_SECONDS) {
                return seconds;
            }
        } catch (NumberFormatException ignored) {
            // Report malformed configuration with the same range as numeric outliers.
        }
        throw new IllegalArgumentException(TIMEOUT_PROPERTY + " must be an integer from 2 to "
                + MAX_TIMEOUT_SECONDS + " seconds");
    }

    private static void waitForCapture(TestContainingApp app, PlatformScenarioCapture capture,
            AtomicReference<Throwable> failure, long deadline) throws InterruptedException {
        while (!capture.complete && failure.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Screenshot rendering failed", failure.get());
        }
        if (!capture.complete) {
            app.stop(false);
            throw new IllegalStateException("Screenshot scenario did not finish");
        }
    }
}
