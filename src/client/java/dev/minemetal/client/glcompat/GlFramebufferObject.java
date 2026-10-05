package dev.minemetal.client.glcompat;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.Arrays;

import static org.lwjgl.opengl.GL46C.*;

/** Emulated framebuffer object: attachment table + draw/read buffer selection. */
final class GlFramebufferObject {
    static final int MAX_COLOR = 8;
    final int id;
    final int[] color = new int[MAX_COLOR];
    final int[] colorLevel = new int[MAX_COLOR];
    int depth;
    /** drawBuffers[fragment output] = color attachment index, -1 = GL_NONE. */
    private final int[] drawBuffers = new int[MAX_COLOR];
    int readBuffer = GL_COLOR_ATTACHMENT0;

    GlFramebufferObject(final int id) {
        this.id = id;
        Arrays.fill(this.drawBuffers, -1);
        this.drawBuffers[0] = 0;
    }

    void attach(final int attachment, final int texture, final int level) {
        if (attachment >= GL_COLOR_ATTACHMENT0 && attachment < GL_COLOR_ATTACHMENT0 + MAX_COLOR) {
            this.color[attachment - GL_COLOR_ATTACHMENT0] = texture;
            this.colorLevel[attachment - GL_COLOR_ATTACHMENT0] = level;
        } else if (attachment == GL_DEPTH_ATTACHMENT || attachment == GL_DEPTH_STENCIL_ATTACHMENT) {
            this.depth = texture;
        }
    }

    void setDrawBuffers(final int[] buffers) {
        Arrays.fill(this.drawBuffers, -1);
        for (int i = 0; i < Math.min(buffers.length, MAX_COLOR); i++) {
            int b = buffers[i];
            this.drawBuffers[i] = b >= GL_COLOR_ATTACHMENT0 && b < GL_COLOR_ATTACHMENT0 + MAX_COLOR ? b - GL_COLOR_ATTACHMENT0 : -1;
        }
    }

    int drawBuffer(final int output) {
        return output < 0 || output >= MAX_COLOR ? -1 : this.drawBuffers[output];
    }

    int readTexture() {
        int index = this.readBuffer - GL_COLOR_ATTACHMENT0;
        return index >= 0 && index < MAX_COLOR ? this.color[index] : 0;
    }

    boolean isComplete(final Int2ObjectMap<GlTextureObject> textures) {
        for (int c : this.color) {
            GlTextureObject t = textures.get(c);
            if (t != null && t.hasStorage()) {
                return true;
            }
        }
        GlTextureObject d = textures.get(this.depth);
        return d != null && d.hasStorage();
    }
}
