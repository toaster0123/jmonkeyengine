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
import com.jme3.app.IosApplicationLauncher;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.ios.JmeIosSystem;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;

/** iOS lifecycle and PNG output adapter; offscreen rendering still uses TestDriver. */
public final class IosPlatformCapture extends IosApplicationLauncher {
    @Override
    protected Application createApplication() {
        JmeSystem.setSystemDelegate(new ScreenshotIosSystem());
        return PlatformScenarioCapture.create(requiredEnvironment("JME_SCREENSHOT_TEST"),
                Integer.parseInt(requiredEnvironment("JME_SCREENSHOT_SCENARIO")),
                Path.of(requiredEnvironment("JME_SCREENSHOT_OUTPUT")), AppSettings.ANGLE_GLES3, false);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing environment variable " + name);
        }
        return value;
    }

    private static final class ScreenshotIosSystem extends JmeIosSystem {
        @Override
        public void writeImageFile(OutputStream output, String format, ByteBuffer input, int width, int height)
                throws IOException {
            if (!"png".equalsIgnoreCase(format)) {
                throw new IOException("Unsupported simulator screenshot format: " + format);
            }
            ByteBuffer pixels = input.duplicate();
            byte[] row = new byte[width * 4];
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream image = new DeflaterOutputStream(compressed)) {
                for (int y = height - 1; y >= 0; y--) {
                    pixels.position(y * row.length);
                    pixels.get(row);
                    image.write(0); // PNG scanline filter: none.
                    image.write(row);
                }
            }
            DataOutputStream png = new DataOutputStream(output);
            png.writeLong(0x89504e470d0a1a0aL);
            ByteBuffer header = ByteBuffer.allocate(13);
            header.putInt(width).putInt(height).put((byte) 8).put((byte) 6);
            chunk(png, "IHDR", header.array());
            chunk(png, "IDAT", compressed.toByteArray());
            chunk(png, "IEND", new byte[0]);
            png.flush();
        }

        private static void chunk(DataOutputStream png, String type, byte[] data) throws IOException {
            byte[] kind = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(kind);
            crc.update(data);
            png.writeInt(data.length);
            png.write(kind);
            png.write(data);
            png.writeInt((int) crc.getValue());
        }
    }
}
