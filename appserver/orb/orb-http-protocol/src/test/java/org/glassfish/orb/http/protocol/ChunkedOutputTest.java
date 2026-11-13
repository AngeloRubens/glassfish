/*
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.orb.http.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedOutputTest {

    @Test
    @DisplayName("produces exactly the bytes a ByteArrayOutputStream would")
    void matchesByteArrayOutputStream() throws IOException {
        Random random = new Random(11L);
        for (int trial = 0; trial < 50; trial++) {
            ChunkedOutput chunked = new ChunkedOutput(64);
            ByteArrayOutputStream reference = new ByteArrayOutputStream();

            for (int op = 0; op < 200; op++) {
                if (random.nextBoolean()) {
                    int b = random.nextInt(256);
                    chunked.write(b);
                    reference.write(b);
                } else {
                    byte[] block = new byte[random.nextInt(200)];
                    random.nextBytes(block);
                    int off = block.length == 0 ? 0 : random.nextInt(block.length);
                    int len = block.length == 0 ? 0 : random.nextInt(block.length - off);
                    chunked.write(block, off, len);
                    reference.write(block, off, len);
                }
            }

            byte[] expected = reference.toByteArray();
            assertEquals(expected.length, chunked.size());
            assertArrayEquals(expected, chunked.toByteArray());
            assertArrayEquals(expected, flatten(chunked.toByteBuffers()));
        }
    }

    @Test
    @DisplayName("the buffers alias the chunks and remain Grizzly-wrappable")
    void buffersAreViewsNotCopies() throws IOException {
        ChunkedOutput out = new ChunkedOutput(8);
        out.write(new byte[20]);

        ByteBuffer[] buffers = out.toByteBuffers();
        // 20 bytes at 8 per chunk: two full chunks plus a partial one.
        assertEquals(3, buffers.length);
        assertEquals(8, buffers[0].remaining());
        assertEquals(8, buffers[1].remaining());
        assertEquals(4, buffers[2].remaining());
        assertTrue(buffers[0].hasArray(), "the transport must be able to wrap the chunk without copying");

        long total = 0;
        for (ByteBuffer b : buffers) {
            total += b.remaining();
        }
        assertEquals(out.size(), total);
    }

    @Test
    void handlesAnEmptyStream() {
        ChunkedOutput out = new ChunkedOutput();
        assertEquals(0, out.size());
        assertEquals(0, out.toByteBuffers().length);
        assertEquals(0, out.toByteArray().length);
    }

    @Test
    void chunkedInputReadsBackWhatChunkedOutputWrote() throws IOException {
        byte[] payload = new byte[5000];
        new Random(3L).nextBytes(payload);

        ChunkedOutput out = new ChunkedOutput(97);
        out.write(payload);

        ChunkedInput in = new ChunkedInput(out.toByteBuffers());
        assertEquals(payload.length, in.available());
        assertArrayEquals(payload, in.readAllBytes());
        assertEquals(-1, in.read());
    }

    @Test
    @DisplayName("ChunkedInput reads across buffer boundaries and honours skip")
    void chunkedInputCrossesBoundaries() throws IOException {
        List<ByteBuffer> parts = new ArrayList<>();
        parts.add(ByteBuffer.wrap(new byte[] { 1, 2, 3 }));
        parts.add(ByteBuffer.wrap(new byte[0]));
        parts.add(ByteBuffer.wrap(new byte[] { 4, 5 }));

        ChunkedInput in = new ChunkedInput(parts);
        byte[] dst = new byte[5];
        assertEquals(5, in.read(dst, 0, 5));
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, dst);

        ChunkedInput skipping = new ChunkedInput(
                ByteBuffer.wrap(new byte[] { 1, 2, 3 }), ByteBuffer.wrap(new byte[] { 4, 5 }));
        assertEquals(4, skipping.skip(4));
        assertEquals(5, skipping.read());
        assertEquals(-1, skipping.read());
    }

    private static byte[] flatten(ByteBuffer[] buffers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ByteBuffer b : buffers) {
            ByteBuffer copy = b.duplicate();
            while (copy.hasRemaining()) {
                out.write(copy.get());
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("frame header at the end of a chunk leaves no padding")
    void frameHeaderAtChunkEndLeavesNoPadding() throws IOException {
        ChunkedOutput out = new ChunkedOutput(8);
        out.write(new byte[] {1, 2, 3, 4, 5});
        ChunkedOutput.Frame f = out.beginFrame((byte) 7);
        out.write(new byte[] {9, 9, 9});
        f.close();

        assertEquals(13L, out.size());
        long buffered = 0;
        for (ByteBuffer b : out.toByteBuffers()) {
            buffered += b.remaining();
        }
        assertEquals(13L, buffered);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 7, 0, 0, 0, 3, 9, 9, 9}, out.toByteArray());
    }

    @Test
    @DisplayName("closing a frame twice leaves the same bytes")
    void closingAFrameTwiceIsIdempotent() throws IOException {
        ChunkedOutput out = new ChunkedOutput(8);
        ChunkedOutput.Frame f = out.beginFrame((byte) 1);
        out.write(new byte[] {1, 2, 3});
        f.close();
        byte[] first = out.toByteArray();
        out.write(new byte[] {4, 5, 6, 7});
        f.close();
        byte[] expected = new byte[] {1, 0, 0, 0, 3, 1, 2, 3, 4, 5, 6, 7};
        assertArrayEquals(expected, out.toByteArray());
        assertArrayEquals(java.util.Arrays.copyOf(expected, 8), java.util.Arrays.copyOf(first, 8));
    }

    @Test
    @DisplayName("a frame spanning several chunks carries the right header")
    void frameSpanningManyChunks() throws IOException {
        ChunkedOutput out = new ChunkedOutput(64);
        ChunkedOutput.Frame f = out.beginFrame((byte) 0);
        byte[] payload = new byte[200];
        new Random(5L).nextBytes(payload);
        out.write(payload);
        f.close();

        byte[] bytes = out.toByteArray();
        assertEquals(205, bytes.length);
        assertEquals(0, bytes[0]);
        assertEquals(200, ByteBuffer.wrap(bytes, 1, 4).getInt());
        assertArrayEquals(payload, java.util.Arrays.copyOfRange(bytes, 5, 205));
        assertArrayEquals(bytes, flatten(out.toByteBuffers()));
    }

    @Test
    @DisplayName("a header may straddle a chunk boundary at any offset")
    void headerSpanningChunkBoundary() throws IOException {
        int chunk = 16;
        for (int prefix = chunk - 4; prefix < chunk; prefix++) {
            ChunkedOutput out = new ChunkedOutput(chunk);
            out.write(new byte[prefix]);
            ChunkedOutput.Frame f = out.beginFrame((byte) 7);
            out.write(new byte[] {9, 9, 9, 9, 9});
            f.close();

            byte[] bytes = out.toByteArray();
            assertEquals(prefix + 10, bytes.length, "prefix " + prefix);
            assertEquals(7, bytes[prefix], "prefix " + prefix);
            assertEquals(5, ByteBuffer.wrap(bytes, prefix + 1, 4).getInt(), "prefix " + prefix);
            assertArrayEquals(bytes, flatten(out.toByteBuffers()), "prefix " + prefix);
            long buffered = 0;
            for (ByteBuffer b : out.toByteBuffers()) {
                buffered += b.remaining();
            }
            assertEquals(out.size(), buffered, "prefix " + prefix);
        }
    }
}
