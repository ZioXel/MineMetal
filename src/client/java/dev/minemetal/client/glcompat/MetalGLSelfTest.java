package dev.minemetal.client.glcompat;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.minemetal.client.metal.MetalDevice;
import dev.minemetal.client.metal.Native;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL46C.*;

/**
 * End-to-end check of the GL emulation on real hardware (run with -Dminemetal.glSelfTest=true):
 * texture upload → GLSL program with loose uniforms in both stages, a varying, a matrix and a sampler →
 * fullscreen draw into an RGBA16F render target → GPU readback → compare with the expected values and orientation.
 */
public final class MetalGLSelfTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("MineMetal/GL");
    private static final int SIZE = 64;

    private static final String VS = """
        #version 330 core
        in vec3 Position;
        in vec2 UV0;
        uniform mat4 transform;
        uniform float scale;
        out vec2 texCoord;
        out float vScale;
        void main() {
            texCoord = UV0;
            vScale = scale;
            gl_Position = transform * vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    private static final String FS = """
        #version 330 core
        in float vScale;
        in vec2 texCoord;
        uniform sampler2D tex;
        uniform vec4 tint;
        layout(location = 0) out vec4 fragColor;
        void main() {
            fragColor = texture(tex, texCoord) * tint * vScale;
        }
        """;

    private MetalGLSelfTest() {
    }

    public static void run() {
        MetalDevice device = MetalDevice.current();
        if (device == null) {
            LOGGER.warn("GL self-test skipped: Metal backend not active");
            return;
        }
        long t0 = System.nanoTime();
        try {
            boolean ok = runChecks(device);
            LOGGER.info("GL self-test {} ({} ms)", ok ? "PASSED" : "FAILED", (System.nanoTime() - t0) / 1_000_000);
        } catch (Throwable t) {
            LOGGER.error("GL self-test FAILED with an exception", t);
        }
    }

    private static boolean runChecks(final MetalDevice device) {
        // 1. Source texture with a recognisable pattern: R = x*4, G = y*4, B = 128, A = 255.
        ByteBuffer pixels = MemoryUtil.memAlloc(SIZE * SIZE * 4);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels.put((byte) (x * 4)).put((byte) (y * 4)).put((byte) 128).put((byte) 255);
            }
        }
        pixels.flip();
        MetalGL.glActiveTexture(GL_TEXTURE0);
        int src = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, src);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        MetalGL.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, SIZE, SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(pixels));
        MemoryUtil.memFree(pixels);

        // 2. Render target + framebuffer
        int dst = MetalGL.glGenTextures();
        MetalGL.glBindTexture(GL_TEXTURE_2D, dst);
        MetalGL.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, SIZE, SIZE, 0, GL_RGBA, GL_FLOAT, 0L);
        int fbo = MetalGL.glGenFramebuffers();
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        MetalGL.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, dst, 0);
        MetalGL.glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});
        if (MetalGL.glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGGER.error("self-test: framebuffer incomplete");
            return false;
        }
        MetalGL.glClearColor(1.0F, 0.0F, 1.0F, 1.0F);
        MetalGL.glClear(GL_COLOR_BUFFER_BIT);

        // 3. Program
        int vs = MetalGL.glCreateShader(GL_VERTEX_SHADER);
        MetalGL.glShaderSource(vs, VS);
        int fs = MetalGL.glCreateShader(GL_FRAGMENT_SHADER);
        MetalGL.glShaderSource(fs, FS);
        int program = MetalGL.glCreateProgram();
        MetalGL.setProgramDebugName(program, "minemetal:selftest");
        MetalGL.glAttachShader(program, vs);
        MetalGL.glAttachShader(program, fs);
        MetalGL.glLinkProgram(program);
        if (MetalGL.glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            LOGGER.error("self-test: link failed: {}", MetalGL.glGetProgramInfoLog(program));
            return false;
        }
        MetalGL.glUseProgram(program);
        float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        FloatBuffer m = MemoryUtil.memAllocFloat(16).put(identity).flip();
        MetalGL.glUniformMatrix4fv(MetalGL.glGetUniformLocation(program, "transform"), false, m);
        MemoryUtil.memFree(m);
        MetalGL.glUniform1f(MetalGL.glGetUniformLocation(program, "scale"), 0.5F);
        MetalGL.glUniform4f(MetalGL.glGetUniformLocation(program, "tint"), 1.0F, 0.5F, 1.0F, 1.0F);
        MetalGL.glUniform1i(MetalGL.glGetUniformLocation(program, "tex"), 0);
        int locs = 0;
        for (String n : new String[]{"transform", "scale", "tint", "tex"}) {
            if (MetalGL.glGetUniformLocation(program, n) < 0) {
                LOGGER.error("self-test: uniform '{}' has no location", n);
            } else {
                locs++;
            }
        }
        MetalGL.glBindTexture(GL_TEXTURE_2D, src);
        MetalGL.glViewport(0, 0, SIZE, SIZE);
        MetalGL.glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L);

        // 4. Read back (RGBA16F)
        GpuTexture target = MetalGL.gpuTexture(dst);
        long bytes = (long) SIZE * SIZE * 8;
        GpuBuffer readback = device.createBuffer(() -> "selftest readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
        device.encoder().copyTextureToBuffer(target, readback, 0L, () -> {}, 0);
        device.encoder().submit();
        Native.waitIdle(device.ctx());
        boolean ok = locs == 4;
        try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
            ByteBuffer data = view.data().order(ByteOrder.nativeOrder());
            int[][] probes = {{0, 0}, {63, 0}, {0, 63}, {17, 42}, {50, 9}};
            for (int[] p : probes) {
                int x = p[0];
                int y = p[1];
                int at = (y * SIZE + x) * 8;
                float r = Float.float16ToFloat(data.getShort(at));
                float g = Float.float16ToFloat(data.getShort(at + 2));
                float b = Float.float16ToFloat(data.getShort(at + 4));
                float a = Float.float16ToFloat(data.getShort(at + 6));
                float er = (x * 4 & 0xFF) / 255.0F * 0.5F;
                float eg = (y * 4 & 0xFF) / 255.0F * 0.5F * 0.5F;
                float eb = 128 / 255.0F * 0.5F;
                float ea = 0.5F;
                boolean match = near(r, er) && near(g, eg) && near(b, eb) && near(a, ea);
                ok &= match;
                LOGGER.info("self-test pixel ({},{}): got ({}, {}, {}, {}) expected ({}, {}, {}, {}) {}", x, y, f(r), f(g), f(b), f(a), f(er), f(eg), f(eb),
                    f(ea), match ? "OK" : "MISMATCH");
            }
        }
        readback.close();
        MetalGL.glDeleteProgram(program);
        MetalGL.glDeleteFramebuffers(fbo);
        MetalGL.glDeleteTextures(src);
        MetalGL.glDeleteTextures(dst);
        MetalGL.glBindFramebuffer(GL_FRAMEBUFFER, 0);
        MetalGL.glUseProgram(0);
        return ok;
    }

    private static boolean near(final float a, final float b) {
        return Math.abs(a - b) < 0.01F;
    }

    private static String f(final float v) {
        return String.format("%.3f", v);
    }
}
