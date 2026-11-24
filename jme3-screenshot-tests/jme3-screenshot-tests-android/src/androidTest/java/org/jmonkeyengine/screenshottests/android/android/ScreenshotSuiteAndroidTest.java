/*
 * Copyright (c) 2026 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
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
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.jmonkeyengine.screenshottests.android.android;

import org.jmonkeyengine.screenshottests.testframework.AndroidRunner;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotSuite;
import org.jmonkeyengine.screenshottests.testframework.ScreenshotTest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

@RunWith(Parameterized.class)
public class ScreenshotSuiteAndroidTest extends ScreenshotTestAndroidBase {

    private static final String PACKAGE_PREFIX = "org.jmonkeyengine.screenshottests.";

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> screenshotCases() {
        Collection<Object[]> cases = new ArrayList<>();
        ScreenshotSuite.all().forEach((id, factory) -> cases.add(new Object[]{id, factory}));
        return cases;
    }

    private final String id;
    private final Supplier<ScreenshotTest> factory;

    public ScreenshotSuiteAndroidTest(String id, Supplier<ScreenshotTest> factory) {
        this.id = id;
        this.factory = factory;
    }

    @Test
    public void screenshot() {
        // Preserve Android's legacy filename namespace so the first comparison against
        // a reference branch without this catalog reuses its existing Android baselines.
        String androidImagePrefix = PACKAGE_PREFIX + "android." + id.substring(PACKAGE_PREFIX.length());
        factory.get().setBaseImageFileName(androidImagePrefix).run(new AndroidRunner());
    }
}
