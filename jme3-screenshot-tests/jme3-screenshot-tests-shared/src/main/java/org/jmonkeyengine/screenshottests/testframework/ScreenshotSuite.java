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
package org.jmonkeyengine.screenshottests.testframework;

import com.jme3.math.Vector3f;
import org.jmonkeyengine.screenshottests.scenarios.animation.ScenarioIssue2076;
import org.jmonkeyengine.screenshottests.scenarios.animation.ScenarioMotionPath;
import org.jmonkeyengine.screenshottests.scenarios.effects.ScenarioExplosionEffect;
import org.jmonkeyengine.screenshottests.scenarios.effects.ScenarioIssue1773;
import org.jmonkeyengine.screenshottests.scenarios.export.ScenarioOgreConvert;
import org.jmonkeyengine.screenshottests.scenarios.gui.ScenarioBitmapText3D;
import org.jmonkeyengine.screenshottests.scenarios.light.pbr.ScenarioPBRLighting;
import org.jmonkeyengine.screenshottests.scenarios.light.pbr.ScenarioPBRSimple;
import org.jmonkeyengine.screenshottests.scenarios.light.pbr.ScenarioPBRSunlight;
import org.jmonkeyengine.screenshottests.scenarios.material.ScenarioSimpleBumps;
import org.jmonkeyengine.screenshottests.scenarios.model.shape.ScenarioBillboard;
import org.jmonkeyengine.screenshottests.scenarios.post.ScenarioCartoonEdge;
import org.jmonkeyengine.screenshottests.scenarios.post.ScenarioFog;
import org.jmonkeyengine.screenshottests.scenarios.post.ScenarioLightScattering;
import org.jmonkeyengine.screenshottests.scenarios.post.ScenarioMultiViewDisplayScale;
import org.jmonkeyengine.screenshottests.scenarios.scene.instancing.ScenarioInstanceNodeWithPbr;
import org.jmonkeyengine.screenshottests.scenarios.terrain.ScenarioPBRTerrain;
import org.jmonkeyengine.screenshottests.scenarios.terrain.ScenarioPBRTerrainAdvanced;
import org.jmonkeyengine.screenshottests.scenarios.texture.ScenarioSingleChannelTexture;
import org.jmonkeyengine.screenshottests.scenarios.water.ScenarioPostWater;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared catalog of visual screenshot tests. Each invocation creates fresh scenarios.
 * Keys are the historical desktop image prefixes, preserving the reference filenames.
 */
public final class ScreenshotSuite {

    private static final String PACKAGE_PREFIX = "org.jmonkeyengine.screenshottests.";
    private static final Map<String, Supplier<ScreenshotTest>> TESTS = createTests();

    private ScreenshotSuite() {
    }

    public static Map<String, Supplier<ScreenshotTest>> all() {
        return TESTS;
    }

    private static Map<String, Supplier<ScreenshotTest>> createTests() {
        Map<String, Supplier<ScreenshotTest>> tests = new LinkedHashMap<>();
        add(tests, "animation.TestIssue2076.testIssue2076",
                ScenarioIssue2076::testIssue2076);
        add(tests, "animation.TestMotionPath.testMotionPath",
                ScenarioMotionPath::testMotionPath);
        add(tests, "effects.TestExplosionEffect.testExplosionEffect",
                ScenarioExplosionEffect::testExplosionEffect);
        add(tests, "effects.TestIssue1773.testIssue1773_worldSpace",
                () -> ScenarioIssue1773.testIssue1773(true));
        add(tests, "effects.TestIssue1773.testIssue1773_localSpace",
                () -> ScenarioIssue1773.testIssue1773(false));
        add(tests, "export.TestOgreConvert.testOgreConvert",
                ScenarioOgreConvert::testOgreConvert);
        add(tests, "gui.TestBitmapText3D.testBitmapText3D",
                ScenarioBitmapText3D::testBitmapText3D);
        add(tests, "light.pbr.TestPBRLighting.testPBRLighting_LowRoughness",
                () -> ScenarioPBRLighting.testPBRLighting(0.1f, false));
        add(tests, "light.pbr.TestPBRLighting.testPBRLighting_HighRoughness",
                () -> ScenarioPBRLighting.testPBRLighting(1.0f, false));
        add(tests, "light.pbr.TestPBRLighting.testPBRLighting_DefaultDirectionalLight",
                () -> ScenarioPBRLighting.testPBRLighting(0.5f, false));
        add(tests, "light.pbr.TestPBRLighting.testPBRLighting_UpdatedDirectionalLight",
                () -> ScenarioPBRLighting.testPBRLighting(0.5f, true));
        add(tests, "light.pbr.TestPBRSimple.testPBRSimple_WithRealtimeBaking",
                () -> ScenarioPBRSimple.testPBRSimple(true));
        add(tests, "light.pbr.TestPBRSimple.testPBRSimple_WithoutRealtimeBaking",
                () -> ScenarioPBRSimple.testPBRSimple(false));
        add(tests, "light.pbr.TestPBRSunlight.testSunlightInputs",
                ScenarioPBRSunlight::testSunlightInputs);
        add(tests, "light.pbr.TestPBRSunlight.testSunlightParallax",
                ScenarioPBRSunlight::testSunlightParallax);
        add(tests, "material.TestSimpleBumps.testSimpleBumps",
                ScenarioSimpleBumps::testSimpleBumps);
        add(tests, "model.shape.TestBillboard.testBillboard_fromFront",
                () -> ScenarioBillboard.testBillboard(new Vector3f(0, 1, 15)));
        add(tests, "model.shape.TestBillboard.testBillboard_fromAbove",
                () -> ScenarioBillboard.testBillboard(new Vector3f(0, 15, 6)));
        add(tests, "model.shape.TestBillboard.testBillboard_fromRight",
                () -> ScenarioBillboard.testBillboard(new Vector3f(-15, 10, 5)));
        add(tests, "post.TestCartoonEdge.testCartoonEdge",
                ScenarioCartoonEdge::testCartoonEdge);
        add(tests, "post.TestFog.testFog",
                ScenarioFog::testFog);
        add(tests, "post.TestLightScattering.testLightScattering",
                ScenarioLightScattering::testLightScattering);
        add(tests, "post.TestMultiViewDisplayScale.testMultiViewDisplayScale",
                ScenarioMultiViewDisplayScale::testMultiViewDisplayScale);
        add(tests, "scene.instancing.TestInstanceNodeWithPbr.testInstanceNodeWithPbr",
                ScenarioInstanceNodeWithPbr::testInstanceNodeWithPbr);
        add(tests, "terrain.TestPBRTerrain.testPBRTerrain_FinalRender",
                () -> ScenarioPBRTerrain.testPBRTerrain(0));
        add(tests, "terrain.TestPBRTerrain.testPBRTerrain_NormalMap",
                () -> ScenarioPBRTerrain.testPBRTerrain(1));
        add(tests, "terrain.TestPBRTerrain.testPBRTerrain_RoughnessMap",
                () -> ScenarioPBRTerrain.testPBRTerrain(2));
        add(tests, "terrain.TestPBRTerrain.testPBRTerrain_MetallicMap",
                () -> ScenarioPBRTerrain.testPBRTerrain(3));
        add(tests, "terrain.TestPBRTerrain.testPBRTerrain_GeometryNormals",
                () -> ScenarioPBRTerrain.testPBRTerrain(8));
        add(tests, "terrain.TestPBRTerrainAdvanced.testPBRTerrainAdvanced_FinalRender",
                () -> ScenarioPBRTerrainAdvanced.testPBRTerrainAdvanced(0));
        add(tests, "terrain.TestPBRTerrainAdvanced.testPBRTerrainAdvanced_AmbientOcclusion",
                () -> ScenarioPBRTerrainAdvanced.testPBRTerrainAdvanced(4));
        add(tests, "terrain.TestPBRTerrainAdvanced.testPBRTerrainAdvanced_Emissive",
                () -> ScenarioPBRTerrainAdvanced.testPBRTerrainAdvanced(5));
        add(tests, "texture.TestSingleChannelTexture.testSingleChannelTexture",
                ScenarioSingleChannelTexture::testSingleChannelTexture);
        add(tests, "water.TestPostWater.testPostWater",
                ScenarioPostWater::testPostWater);
        return Collections.unmodifiableMap(tests);
    }

    private static void add(Map<String, Supplier<ScreenshotTest>> tests, String name,
            Supplier<ScreenshotTest> factory) {
        String imagePrefix = PACKAGE_PREFIX + name;
        if (tests.putIfAbsent(imagePrefix, () -> factory.get().setBaseImageFileName(imagePrefix)) != null) {
            throw new IllegalStateException("Duplicate screenshot test: " + imagePrefix);
        }
    }
}
