package dev.minemetal.neo.gl;

import org.lwjgl.system.MemoryUtil;

/**
 * Upcall targets: one static method per GL entry point, with the C signature of that function (pointers as long,
 * GLboolean as byte). Registered in {@link NeoGL}; exceptions are caught there.
 */
@SuppressWarnings("unused")
final class GlEntry {
    private static final long STR_VENDOR = MemoryUtil.memAddress(MemoryUtil.memASCII("MineMetal"));
    private static final long STR_RENDERER = MemoryUtil.memAddress(MemoryUtil.memASCII("Apple GPU (MineMetal Metal)"));
    private static final long STR_VERSION = MemoryUtil.memAddress(MemoryUtil.memASCII("4.1 MineMetal"));
    private static final long STR_GLSL = MemoryUtil.memAddress(MemoryUtil.memASCII("4.10"));
    private static final long STR_EMPTY = MemoryUtil.memAddress(MemoryUtil.memASCII(""));
    /** Extensions MineMetal really implements (glGetStringi(GL_EXTENSIONS, i)); everything else takes the GL 4.1 core path. */
    private static final long[] EXTENSIONS = {MemoryUtil.memAddress(MemoryUtil.memASCII("GL_EXT_texture_filter_anisotropic"))};
    private static final long SCRATCH = MemoryUtil.nmemAlloc(64);

    private GlEntry() {
    }

    // ---- queries ---------------------------------------------------------------------------------------------------

    static int glGetError() {
        return 0;
    }

    static long glGetString(int name) {
        return switch (name) {
            case 0x1F00 -> STR_VENDOR;
            case 0x1F01 -> STR_RENDERER;
            case 0x1F02 -> STR_VERSION;
            case 0x8B8C -> STR_GLSL;
            default -> STR_EMPTY;
        };
    }

    static long glGetStringi(int name, int index) {
        return name == 0x1F03 && index >= 0 && index < EXTENSIONS.length ? EXTENSIONS[index] : STR_EMPTY;
    }

    private static int integer(int pname) {
        Integer tracked = Gl.stateInteger(pname);
        if (tracked != null) return tracked;
        return switch (pname) {
            case 0x821B -> 4;            // GL_MAJOR_VERSION
            case 0x821C -> 1;            // GL_MINOR_VERSION
            case 0x821D -> EXTENSIONS.length; // GL_NUM_EXTENSIONS
            case 0x821E -> 1;            // GL_CONTEXT_FLAGS: forward compatible
            case 0x9126 -> 1;            // GL_CONTEXT_PROFILE_MASK: core
            case 0x0D33, 0x84E8, 0x851C -> 16384; // MAX_TEXTURE_SIZE, MAX_RENDERBUFFER_SIZE, MAX_CUBE_MAP_TEXTURE_SIZE
            case 0x8073 -> 2048;         // MAX_3D_TEXTURE_SIZE
            case 0x88FF -> 2048;         // MAX_ARRAY_TEXTURE_LAYERS
            // Metal allows 31+ textures per stage; sampler *states* are shared (16 slots) by the program compiler.
            case 0x8872, 0x8B4C -> 32;   // MAX_TEXTURE_IMAGE_UNITS, MAX_VERTEX_TEXTURE_IMAGE_UNITS
            case 0x8B4D -> 48;           // MAX_COMBINED_TEXTURE_IMAGE_UNITS
            case 0x8869 -> 16;           // MAX_VERTEX_ATTRIBS
            case 0x8824, 0x8CDF -> 8;    // MAX_DRAW_BUFFERS, MAX_COLOR_ATTACHMENTS
            case 0x8D57 -> 4;            // MAX_SAMPLES
            case 0x8A30 -> 65536;        // MAX_UNIFORM_BLOCK_SIZE
            case 0x8A2F, 0x8A2B, 0x8A2D -> 14;
            case 0x8A34 -> 256;          // UNIFORM_BUFFER_OFFSET_ALIGNMENT
            case 0x8B4A, 0x8B49 -> 4096; // MAX_VERTEX/FRAGMENT_UNIFORM_COMPONENTS
            case 0x0CF5 -> Gl.unpackAlignment;
            case 0x0D05 -> Gl.packAlignment; // PACK_ALIGNMENT
            case 0x84FF -> 16;           // MAX_TEXTURE_MAX_ANISOTROPY
            default -> 0;
        };
    }

    private static int count(int pname) {
        return switch (pname) {
            case 0x0BA2, 0x0C10, 0x0C22, 0x0C23 -> 4;
            case 0x0B70, 0x846D, 0x846E, 0x0D3A -> 2;
            default -> 1;
        };
    }

    static void glGetIntegerv(int pname, long ptr) {
        if (ptr == 0L) return;
        if (pname == 0x0BA2) { Gl.putViewport(ptr); return; }
        if (pname == 0x0D3A) { MemoryUtil.memPutInt(ptr, 16384); MemoryUtil.memPutInt(ptr + 4, 16384); return; }
        int n = count(pname);
        MemoryUtil.memPutInt(ptr, integer(pname));
        for (int i = 1; i < n; i++) MemoryUtil.memPutInt(ptr + 4L * i, 0);
    }

    static void glGetInteger64v(int pname, long ptr) {
        if (ptr == 0L) return;
        for (int i = 0; i < count(pname); i++) MemoryUtil.memPutLong(ptr + 8L * i, i == 0 ? integer(pname) : 0);
    }

    static void glGetFloatv(int pname, long ptr) {
        if (ptr == 0L) return;
        if (pname == 0x0C22) { for (int i = 0; i < 4; i++) MemoryUtil.memPutFloat(ptr + 4L * i, Gl.clearColor[i]); return; }
        if (pname == 0x84FF) { MemoryUtil.memPutFloat(ptr, 16f); return; }
        for (int i = 0; i < count(pname); i++) MemoryUtil.memPutFloat(ptr + 4L * i, i == 0 ? integer(pname) : 0f);
    }

    static void glGetDoublev(int pname, long ptr) {
        if (ptr == 0L) return;
        for (int i = 0; i < count(pname); i++) MemoryUtil.memPutDouble(ptr + 8L * i, i == 0 ? integer(pname) : 0.0);
    }

    static void glGetBooleanv(int pname, long ptr) {
        if (ptr == 0L) return;
        for (int i = 0; i < count(pname); i++) {
            boolean v = pname == 0x0C23 ? (Gl.colorMask & (1 << i)) != 0 : pname == 0x0B72 ? Gl.depthMask : Gl.isEnabled(pname);
            MemoryUtil.memPutByte(ptr + i, (byte) (v ? 1 : 0));
        }
    }

    static byte glIsEnabled(int cap) {
        return (byte) (Gl.isEnabled(cap) ? 1 : 0);
    }

    static byte glIsTexture(int id) {
        return (byte) (Gl.isTexture(id) ? 1 : 0);
    }

    // ---- names -----------------------------------------------------------------------------------------------------

    static void gen(int n, long ptr) {
        if (ptr == 0L) return;
        for (int i = 0; i < n; i++) MemoryUtil.memPutInt(ptr + 4L * i, Gl.gen());
    }

    static void glDeleteTextures(int n, long ptr) {
        Gl.deleteTextures(n, ptr);
    }

    static void glDeleteBuffers(int n, long ptr) {
        for (int id : Gl.names(n, ptr)) Gl.deleteBuffer(id);
    }

    static void glDeleteFramebuffers(int n, long ptr) {
        for (int id : Gl.names(n, ptr)) Gl.deleteFramebuffer(id);
    }

    static void glDeleteVertexArrays(int n, long ptr) {
        for (int id : Gl.names(n, ptr)) Gl.deleteVertexArray(id);
    }

    static void ignoreNames(int n, long ptr) {
    }

    // ---- fixed-function state --------------------------------------------------------------------------------------

    static void glEnable(int cap) {
        Gl.enable(cap, true);
    }

    static void glDisable(int cap) {
        Gl.enable(cap, false);
    }

    static void glViewport(int x, int y, int w, int h) {
        Gl.viewport[0] = x;
        Gl.viewport[1] = y;
        Gl.viewport[2] = w;
        Gl.viewport[3] = h;
    }

    static void glScissor(int x, int y, int w, int h) {
        Gl.scissor[0] = x;
        Gl.scissor[1] = y;
        Gl.scissor[2] = w;
        Gl.scissor[3] = h;
    }

    static void glClearColor(float r, float g, float b, float a) {
        Gl.clearColor[0] = r;
        Gl.clearColor[1] = g;
        Gl.clearColor[2] = b;
        Gl.clearColor[3] = a;
    }

    static void glClearDepth(double d) {
        Gl.clearDepth = d;
    }

    static void glClearDepthf(float d) {
        Gl.clearDepth = d;
    }

    static void glClear(int mask) {
        Gl.clear(mask);
    }

    static void glClearBufferfv(int buffer, int drawBuffer, long value) {
        Gl.clearBuffer(buffer, drawBuffer, value);
    }

    static void glEnablei(int cap, int index) {
        if (cap == 0x0BE2) Gl.setBlend(index, true);
    }

    static void glDisablei(int cap, int index) {
        if (cap == 0x0BE2) Gl.setBlend(index, false);
    }

    static void glColorMaski(int index, byte r, byte g, byte b, byte a) {
        Gl.setColorMask(index, (r != 0 ? 1 : 0) | (g != 0 ? 2 : 0) | (b != 0 ? 4 : 0) | (a != 0 ? 8 : 0));
    }

    static void glBlendFunc(int src, int dst) {
        Gl.dirty();
        Gl.blendSrcRgb = Gl.blendSrcA = src;
        Gl.blendDstRgb = Gl.blendDstA = dst;
    }

    static void glBlendFuncSeparate(int srcRgb, int dstRgb, int srcA, int dstA) {
        Gl.dirty();
        Gl.blendSrcRgb = srcRgb;
        Gl.blendDstRgb = dstRgb;
        Gl.blendSrcA = srcA;
        Gl.blendDstA = dstA;
    }

    static void glBlendEquation(int mode) {
        Gl.dirty();
        Gl.blendOpRgb = Gl.blendOpA = mode;
    }

    static void glBlendEquationSeparate(int rgb, int a) {
        Gl.dirty();
        Gl.blendOpRgb = rgb;
        Gl.blendOpA = a;
    }

    static void glDepthFunc(int f) {
        Gl.dirty();
        Gl.depthFunc = f;
    }

    static void glDepthMask(byte flag) {
        Gl.dirty();
        Gl.depthMask = flag != 0;
    }

    static void glColorMask(byte r, byte g, byte b, byte a) {
        Gl.setColorMask(-1, (r != 0 ? 1 : 0) | (g != 0 ? 2 : 0) | (b != 0 ? 4 : 0) | (a != 0 ? 8 : 0));
    }

    static void glCullFace(int mode) {
        Gl.dirty();
        Gl.cullMode = mode;
    }

    static void glFrontFace(int mode) {
        Gl.dirty();
        Gl.frontFace = mode;
    }

    static void glPolygonOffset(float factor, float units) {
        Gl.dirty();
        Gl.offsetFactor = factor;
        Gl.offsetUnits = units;
    }

    static void glPixelStorei(int pname, int value) {
        switch (pname) {
            case 0x0CF2 -> Gl.unpackRowLength = value;
            case 0x0CF3 -> Gl.unpackSkipRows = value;
            case 0x0CF4 -> Gl.unpackSkipPixels = value;
            case 0x0CF5 -> Gl.unpackAlignment = value;
            case 0x0D02 -> Gl.packRowLength = value;
            case 0x0D05 -> Gl.packAlignment = value;
            default -> {
            }
        }
    }

    static void glActiveTexture(int unit) {
        int u = unit - 0x84C0;
        if (u >= 0 && u < Gl.units.length) Gl.activeUnit = u;
    }

    // ---- buffers ---------------------------------------------------------------------------------------------------

    static void glBindBuffer(int target, int buffer) {
        Gl.bindBuffer(target, buffer);
    }

    static void glBufferData(int target, long size, long data, int usage) {
        Gl.bufferData(target, size, data);
    }

    static void glBufferSubData(int target, long offset, long size, long data) {
        Gl.bufferSubData(target, offset, size, data);
    }

    static void glGetBufferParameteriv(int target, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, pname == 0x8764 ? (int) Gl.bufferSize(target) : 0);
    }

    static void glGetBufferParameteri64v(int target, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutLong(ptr, pname == 0x8764 ? Gl.bufferSize(target) : 0L);
    }

    static long glMapBufferRange(int target, long offset, long length, int access) {
        return Gl.mapBuffer(target, offset, length, access);
    }

    static long glMapBuffer(int target, int access) {
        return Gl.mapBuffer(target, 0, -1);
    }

    static byte glUnmapBuffer(int target) {
        Gl.unmapBuffer(target);
        return 1;
    }

    // ---- vertex arrays ---------------------------------------------------------------------------------------------

    static void glBindVertexArray(int id) {
        Gl.bindVertexArray(id);
    }

    static void glEnableVertexAttribArray(int index) {
        Gl.enableAttrib(index, true);
    }

    static void glDisableVertexAttribArray(int index) {
        Gl.enableAttrib(index, false);
    }

    static void glVertexAttribPointer(int index, int size, int type, byte normalized, int stride, long offset) {
        Gl.attribPointer(index, size, type, normalized != 0, false, stride, offset);
    }

    static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) {
        Gl.attribPointer(index, size, type, false, true, stride, offset);
    }

    // ---- textures --------------------------------------------------------------------------------------------------

    static void glBindTexture(int target, int id) {
        Gl.bindTexture(target, id);
    }

    static void glTexImage2D(int target, int level, int ifmt, int w, int h, int border, int format, int type, long pixels) {
        Gl.texImage2D(target, level, ifmt, w, h, format, type, pixels);
    }

    static void glTexSubImage2D(int target, int level, int x, int y, int w, int h, int format, int type, long pixels) {
        Gl.texSubImage2D(target, level, x, y, w, h, format, type, pixels);
    }

    static void glTexParameteri(int target, int pname, int value) {
        Gl.texParameter(target, pname, value);
    }

    static void glTexParameterf(int target, int pname, float value) {
        Gl.texParameterf(target, pname, value);
    }

    static void glGenerateMipmap(int target) {
        Gl.generateMipmap();
    }

    static void glGetTexLevelParameteriv(int target, int level, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, target == 0x8064 ? (pname == 0x1000 || pname == 0x1001 ? Gl.proxyWidth : 0) : Gl.texLevelParameter(level, pname));
    }

    static void writeZero3(int a, int b, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, 0);
    }

    static void writeZero4(int a, int b, int c, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, 0);
    }

    static void glReadPixels(int x, int y, int w, int h, int format, int type, long ptr) {
        Gl.readPixels(x, y, w, h, format, type, ptr);
    }

    static void glGetTexImage(int target, int level, int format, int type, long ptr) {
    }

    // ---- framebuffers ----------------------------------------------------------------------------------------------

    static void glBindFramebuffer(int target, int id) {
        Gl.bindFramebuffer(target, id);
    }

    static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        Gl.framebufferTexture(target, attachment, texture, level);
    }

    static void glFramebufferRenderbuffer(int target, int attachment, int rbTarget, int rb) {
        Gl.framebufferRenderbuffer(target, attachment, rb);
    }

    static void glBindRenderbuffer(int target, int id) {
        Gl.bindRenderbuffer(id);
    }

    static void glRenderbufferStorage(int target, int ifmt, int w, int h) {
        Gl.renderbufferStorage(ifmt, w, h);
    }

    static int glCheckFramebufferStatus(int target) {
        return 0x8CD5;
    }

    static void glBlitFramebuffer(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        Gl.blitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask);
    }

    // ---- shaders ---------------------------------------------------------------------------------------------------

    static int glCreateShader(int type) {
        return Gl.createShader(type);
    }

    static void glShaderSource(int shader, int count, long strings, long lengths) {
        Gl.shaderSource(shader, count, strings, lengths);
    }

    static void glDeleteShader(int shader) {
        Gl.deleteShader(shader);
    }

    static int glCreateProgram() {
        return Gl.createProgram();
    }

    static void glAttachShader(int program, int shader) {
        Gl.attachShader(program, shader);
    }

    static void glBindAttribLocation(int program, int index, long name) {
        Gl.bindAttribLocation(program, index, MemoryUtil.memUTF8(name));
    }

    static void glLinkProgram(int program) {
        Gl.linkProgram(program);
    }

    static void glUseProgram(int program) {
        Gl.useProgram(program);
    }

    static void glDeleteProgram(int program) {
        Gl.deleteProgram(program);
    }

    static void glGetShaderiv(int shader, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, pname == 0x8B81 ? 1 : 0); // COMPILE_STATUS: real work happens at link
    }

    static void glGetProgramiv(int program, int pname, long ptr) {
        if (ptr == 0L) return;
        // A failed translation still reports success so the game keeps running; the error is logged and the
        // program's draws are skipped.
        int v = switch (pname) {
            case 0x8B82, 0x8B83 -> 1;
            case 0x8B84 -> Gl.infoLog(program).length() + 1;
            default -> 0;
        };
        MemoryUtil.memPutInt(ptr, v);
    }

    static void glGetShaderInfoLog(int shader, int maxLength, long lengthPtr, long buf) {
        if (lengthPtr != 0L) MemoryUtil.memPutInt(lengthPtr, 0);
        if (buf != 0L && maxLength > 0) MemoryUtil.memPutByte(buf, (byte) 0);
    }

    static void glGetProgramInfoLog(int program, int maxLength, long lengthPtr, long buf) {
        String log = Gl.infoLog(program);
        byte[] bytes = log.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.max(0, Math.min(bytes.length, maxLength - 1));
        if (buf != 0L && maxLength > 0) {
            for (int i = 0; i < n; i++) MemoryUtil.memPutByte(buf + i, bytes[i]);
            MemoryUtil.memPutByte(buf + n, (byte) 0);
        }
        if (lengthPtr != 0L) MemoryUtil.memPutInt(lengthPtr, n);
    }

    static int glGetUniformLocation(int program, long name) {
        return Gl.uniformLocation(program, MemoryUtil.memUTF8(name));
    }

    static int glGetAttribLocation(int program, long name) {
        return Gl.attribLocation(program, MemoryUtil.memUTF8(name));
    }

    static int glGetUniformBlockIndex(int program, long name) {
        return Gl.uniformBlockIndex(program, MemoryUtil.memUTF8(name));
    }

    static void glUniformBlockBinding(int program, int index, int binding) {
        Gl.uniformBlockBinding(program, index, binding);
    }

    static void glBindBufferBase(int target, int index, int buffer) {
        Gl.bindBufferRange(target, index, buffer, 0L, -1L);
    }

    static void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        Gl.bindBufferRange(target, index, buffer, offset, size);
    }

    static void glTexBuffer(int target, int ifmt, int buffer) {
        Gl.texBuffer(ifmt, buffer, 0L, -1L);
    }

    static void glTexBufferRange(int target, int ifmt, int buffer, long offset, long size) {
        Gl.texBuffer(ifmt, buffer, offset, size);
    }

    static void glTexParameteriv(int target, int pname, long ptr) {
        if (ptr != 0L) Gl.texParameter(target, pname, MemoryUtil.memGetInt(ptr));
    }

    static void glCopyTexSubImage2D(int target, int level, int xoff, int yoff, int x, int y, int w, int h) {
        Gl.copyTexSubImage(level, xoff, yoff, x, y, w, h);
    }

    static void glCopyTexImage2D(int target, int level, int ifmt, int x, int y, int w, int h, int border) {
        Gl.copyTexImage(level, ifmt, x, y, w, h);
    }

    static void glBindSampler(int unit, int sampler) {
        if (unit >= 0 && unit < Gl.boundSamplerObjs.length) Gl.boundSamplerObjs[unit] = sampler;
    }

    static void glSamplerParameteri(int sampler, int pname, int value) {
        Gl.samplerParameter(sampler, pname, value);
    }

    static void glSamplerParameterf(int sampler, int pname, float value) {
        Gl.samplerParameter(sampler, pname, value);
    }

    static void glVertexAttribDivisor(int index, int divisor) {
        Gl.attribDivisor(index, divisor);
    }

    static void glDrawArraysInstanced(int mode, int first, int count, int instances) {
        Gl.drawInstanced(mode, count, 0, 0L, 0, instances, false, first);
    }

    static void glDrawElementsInstanced(int mode, int count, int type, long indices, int instances) {
        Gl.drawInstanced(mode, count, type, indices, 0, instances, true, 0);
    }

    static void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int instances, int base) {
        Gl.drawInstanced(mode, count, type, indices, base, instances, true, 0);
    }

    static int minusOneIJ(int a, long b) {
        return -1;
    }

    // ---- uniforms --------------------------------------------------------------------------------------------------

    private static void f(int loc, int n, float a, float b, float c, float d) {
        MemoryUtil.memPutFloat(SCRATCH, a);
        MemoryUtil.memPutFloat(SCRATCH + 4, b);
        MemoryUtil.memPutFloat(SCRATCH + 8, c);
        MemoryUtil.memPutFloat(SCRATCH + 12, d);
        Gl.uniform(loc, 1, n, SCRATCH, false);
    }

    private static void i(int loc, int n, int a, int b, int c, int d) {
        MemoryUtil.memPutInt(SCRATCH, a);
        MemoryUtil.memPutInt(SCRATCH + 4, b);
        MemoryUtil.memPutInt(SCRATCH + 8, c);
        MemoryUtil.memPutInt(SCRATCH + 12, d);
        Gl.uniform(loc, 1, n, SCRATCH, true);
    }

    static void uniform1f(int l, float a) { f(l, 1, a, 0, 0, 0); }
    static void uniform2f(int l, float a, float b) { f(l, 2, a, b, 0, 0); }
    static void uniform3f(int l, float a, float b, float c) { f(l, 3, a, b, c, 0); }
    static void uniform4f(int l, float a, float b, float c, float d) { f(l, 4, a, b, c, d); }
    static void uniform1i(int l, int a) { i(l, 1, a, 0, 0, 0); }
    static void uniform2i(int l, int a, int b) { i(l, 2, a, b, 0, 0); }
    static void uniform3i(int l, int a, int b, int c) { i(l, 3, a, b, c, 0); }
    static void uniform4i(int l, int a, int b, int c, int d) { i(l, 4, a, b, c, d); }
    static void uniform1fv(int l, int count, long p) { Gl.uniform(l, count, 1, p, false); }
    static void uniform2fv(int l, int count, long p) { Gl.uniform(l, count, 2, p, false); }
    static void uniform3fv(int l, int count, long p) { Gl.uniform(l, count, 3, p, false); }
    static void uniform4fv(int l, int count, long p) { Gl.uniform(l, count, 4, p, false); }
    static void uniform1iv(int l, int count, long p) { Gl.uniform(l, count, 1, p, true); }
    static void uniform2iv(int l, int count, long p) { Gl.uniform(l, count, 2, p, true); }
    static void uniform3iv(int l, int count, long p) { Gl.uniform(l, count, 3, p, true); }
    static void uniform4iv(int l, int count, long p) { Gl.uniform(l, count, 4, p, true); }

    static void glUniformMatrix2fv(int l, int count, byte transpose, long p) { Gl.uniformMatrix(l, count, 2, 2, transpose != 0, p); }
    static void glUniformMatrix3fv(int l, int count, byte transpose, long p) { Gl.uniformMatrix(l, count, 3, 3, transpose != 0, p); }
    static void glUniformMatrix4fv(int l, int count, byte transpose, long p) { Gl.uniformMatrix(l, count, 4, 4, transpose != 0, p); }

    // ---- draws -----------------------------------------------------------------------------------------------------

    static void glDrawArrays(int mode, int first, int count) {
        Gl.draw(mode, count, 0, 0L, first, false);
    }

    static void glDrawElements(int mode, int count, int type, long indices) {
        Gl.draw(mode, count, type, indices, 0, true);
    }

    static void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int base) {
        Gl.drawElementsBaseVertex(mode, count, type, indices, base);
    }

    static void glMultiDrawElementsBaseVertex(int mode, long counts, int type, long indices, int drawCount, long base) {
        Gl.multiDrawElements(mode, counts, type, indices, drawCount, base);
    }

    static void glMultiDrawElements(int mode, long counts, int type, long indices, int drawCount) {
        Gl.multiDrawElements(mode, counts, type, indices, drawCount, 0L);
    }

    static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        Gl.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    static void ignoreIIJ(int a, int b, long c) {
    }

    static void glDeleteSync(long sync) {
        Gl.deleteSync(sync);
    }

    // ---- sync / queries / no-ops -----------------------------------------------------------------------------------

    static long glFenceSync(int condition, int flags) {
        return Gl.fenceSync();
    }

    static int glClientWaitSync(long sync, int flags, long timeout) {
        return Gl.clientWaitSync(sync, flags, timeout);
    }

    static void glGetSynciv(long sync, int pname, int bufSize, long length, long values) {
        if (length != 0L) MemoryUtil.memPutInt(length, 1);
        if (values != 0L && bufSize > 0) {
            int v = switch (pname) {
                case 0x9114 -> Gl.fenceSignaled(sync) ? 0x9119 : 0x9118; // SYNC_STATUS: SIGNALED / UNSIGNALED
                case 0x9112 -> 0x9116;                                  // OBJECT_TYPE: SYNC_FENCE
                case 0x9113 -> 0x9117;                                  // SYNC_CONDITION: GPU_COMMANDS_COMPLETE
                default -> 0;
            };
            MemoryUtil.memPutInt(values, v);
        }
    }

    static void queryObject(int id, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutInt(ptr, pname == 0x8867 ? 1 : 0);
    }

    static void queryObject64(int id, int pname, long ptr) {
        if (ptr != 0L) MemoryUtil.memPutLong(ptr, pname == 0x8867 ? 1L : 0L);
    }

    static void ignore0() {
    }

    static void ignore1(int a) {
    }

    static void ignoreIJJ(int a, long b, long c) {
    }

    static void ignore2(int a, int b) {
    }

    static void ignoreF(float a) {
    }

    static void ignoreJ(long a) {
    }
}
