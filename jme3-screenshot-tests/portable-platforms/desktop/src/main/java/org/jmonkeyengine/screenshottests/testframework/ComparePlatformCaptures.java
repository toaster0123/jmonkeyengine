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
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Host-side comparison with the same pixel tolerance as the other screenshot jobs. */
public final class ComparePlatformCaptures {
    private ComparePlatformCaptures() {
    }

    public static void main(String[] args) throws IOException {
        Path reference = Path.of(args[0]);
        Path target = Path.of(args[1]);
        Path report = Path.of(args[2]);
        for (String scenario : List.of("bitmap-text", "simple-bumps")) {
            Path oldDir = reference.resolve(scenario);
            Path newDir = target.resolve(scenario);
            List<String> oldFrames = completedFrames(oldDir);
            List<String> newFrames = completedFrames(newDir);
            if (!oldFrames.equals(newFrames)) {
                throw new IOException("Mismatched frame sets for " + scenario + ": "
                        + oldFrames + " vs " + newFrames);
            }
            for (String frame : oldFrames) {
                Image before = read(oldDir.resolve(frame));
                Image after = read(newDir.resolve(frame));
                String name = frame.replace(".rgba", ".png");
                ScreenshotImageIo.writePng(before, report.resolve(scenario).resolve("reference-" + name));
                ScreenshotImageIo.writePng(after, report.resolve(scenario).resolve("actual-" + name));
                String result;
                if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
                    result = "changed dimensions";
                } else {
                    ImageDifference difference = ImageDifference.of(before, after);
                    result = difference.isNegligible() ? "matched" : "changed pixels: " + difference.getDifferentPixels();
                }
                System.out.println(scenario + "/" + frame + ": " + result);
            }
        }
    }

    private static List<String> completedFrames(Path directory) throws IOException {
        int expected = Integer.parseInt(Files.readString(directory.resolve("complete")).trim());
        List<String> frames;
        try (Stream<Path> paths = Files.list(directory)) {
            frames = paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("f[0-9]+\\.rgba")).sorted().collect(Collectors.toList());
        }
        if (expected < 1 || frames.size() != expected) {
            throw new IOException("Incomplete screenshot capture: " + directory);
        }
        return frames;
    }

    private static Image read(Path file) throws IOException {
        try (DataInputStream stream = new DataInputStream(Files.newInputStream(file))) {
            int width = stream.readInt();
            int height = stream.readInt();
            if (width < 1 || height < 1 || width > 4096 || height > 4096
                    || Files.size(file) != 8L + 4L * width * height) {
                throw new IOException("Invalid screenshot dimensions or length: " + file);
            }
            byte[] bytes = stream.readNBytes(width * height * 4);
            if (bytes.length != width * height * 4) {
                throw new IOException("Incomplete screenshot: " + file);
            }
            boolean hasContent = false;
            for (int index = 0; index < bytes.length; index += 4) {
                if (bytes[index] != 0 || bytes[index + 1] != 0 || bytes[index + 2] != 0) {
                    hasContent = true;
                    break;
                }
            }
            if (!hasContent) {
                throw new IOException("Blank screenshot: " + file);
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(bytes.length);
            pixels.put(bytes).flip();
            return new Image(Image.Format.RGBA8, width, height, pixels, ColorSpace.sRGB);
        }
    }
}
