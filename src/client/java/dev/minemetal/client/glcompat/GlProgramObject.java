package dev.minemetal.client.glcompat;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Emulated GL program: compiled Metal shaders, a CPU copy of each stage's loose-uniform block (written by glUniform*),
 * sampler → texture-unit assignments, and a cache of Metal pipelines keyed by render state.
 */
final class GlProgramObject {
    /** A named uniform: block offsets per stage (-1 if absent) or, for samplers, the shared Metal slot. */
    static final class Slot {
        final String name;
        final int[] offset = {-1, -1};
        GlProgramCompiler.@Nullable BlockMember type;
        boolean sampler;
        int samplerIndex = -1;
        int unit;

        Slot(final String name) {
            this.name = name;
        }
    }

    final int id;
    final List<Integer> shaders = new ArrayList<>();
    final Object2IntOpenHashMap<String> attribBindings = new Object2IntOpenHashMap<>();
    boolean linked;
    String infoLog = "";
    GlProgramCompiler.@Nullable Result compiled;
    final List<Slot> slots = new ArrayList<>();
    private final Object2IntOpenHashMap<String> slotByName = new Object2IntOpenHashMap<>();
    final ByteBuffer[] blocks = new ByteBuffer[2];
    /** Bumped whenever a loose uniform changes; lets draws reuse the last GPU copy of the blocks. */
    int uniformVersion;
    final int[] uploadedVersion = {-1, -1};
    final long[] uploadedSubmit = {-1L, -1L};
    final long[] uploadedHandle = new long[2];
    final long[] uploadedOffset = new long[2];
    final Object2LongOpenHashMap<String> pipelines = new Object2LongOpenHashMap<>();
    @Nullable String debugName;

    GlProgramObject(final int id) {
        this.id = id;
        this.slotByName.defaultReturnValue(-1);
    }

    void setCompiled(final GlProgramCompiler.Result result) {
        this.freeBlocks();
        this.slots.clear();
        this.slotByName.clear();
        this.compiled = result;
        GlProgramCompiler.StageResult[] stages = {result.vertex(), result.fragment()};
        for (int stage = 0; stage < 2; stage++) {
            GlProgramCompiler.StageResult s = stages[stage];
            if (s.blockSize > 0) {
                this.blocks[stage] = MemoryUtil.memCalloc(s.blockSize);
            }
            for (GlProgramCompiler.BlockMember m : s.members) {
                Slot slot = this.slotFor(m.name());
                slot.offset[stage] = m.offset();
                if (slot.type == null) {
                    slot.type = m;
                }
            }
        }
        this.blockNames.clear();
        for (String n : result.vertex().extraBlocks.keySet()) {
            if (!this.blockNames.contains(n)) {
                this.blockNames.add(n);
            }
        }
        for (String n : result.fragment().extraBlocks.keySet()) {
            if (!this.blockNames.contains(n)) {
                this.blockNames.add(n);
            }
        }
        this.blockBindings = new int[this.blockNames.size()];
        java.util.Arrays.fill(this.blockBindings, -1);
        List<String> samplers = result.samplers();
        for (int i = 0; i < samplers.size(); i++) {
            Slot slot = this.slotFor(samplers.get(i));
            slot.sampler = true;
            slot.samplerIndex = i;
        }
        this.linked = true;
    }

    private Slot slotFor(final String name) {
        int index = this.slotByName.getInt(name);
        if (index >= 0) {
            return this.slots.get(index);
        }
        Slot slot = new Slot(name);
        this.slotByName.put(name, this.slots.size());
        this.slots.add(slot);
        return slot;
    }

    /** GL location = slot index &lt;&lt; 8 | array element; -1 when the uniform does not exist (or was optimised away). */
    int location(final String name) {
        String base = name;
        int element = 0;
        int bracket = name.indexOf('[');
        if (bracket > 0 && name.endsWith("]")) {
            base = name.substring(0, bracket);
            try {
                element = Integer.parseInt(name.substring(bracket + 1, name.length() - 1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int index = this.slotByName.getInt(base);
        if (index < 0 || element > 255) {
            return -1;
        }
        return index << 8 | element;
    }

    @Nullable Slot slot(final int location) {
        int index = location >> 8;
        return location < 0 || index >= this.slots.size() ? null : this.slots.get(index);
    }

    void setFloats(final int location, final float[] values, final int components, final int count) {
        Slot slot = this.slot(location);
        if (slot == null || slot.type == null) {
            return;
        }
        this.uniformVersion++;
        GlProgramCompiler.BlockMember t = slot.type;
        int element = location & 0xFF;
        for (int stage = 0; stage < 2; stage++) {
            if (slot.offset[stage] < 0 || this.blocks[stage] == null) {
                continue;
            }
            long base = MemoryUtil.memAddress(this.blocks[stage]);
            int stride = t.arrayStride() > 0 ? t.arrayStride() : 16;
            for (int e = 0; e < count; e++) {
                long at = base + slot.offset[stage] + (long) (element + e) * (t.arraySize() > 0 ? stride : 0);
                if (at + components * 4L > base + this.blocks[stage].capacity()) {
                    break;
                }
                for (int c = 0; c < components; c++) {
                    float v = values[e * components + c];
                    if (GlProgramCompiler.isIntegerLike(t.baseType())) {
                        MemoryUtil.memPutInt(at + c * 4L, (int) v);
                    } else {
                        MemoryUtil.memPutFloat(at + c * 4L, v);
                    }
                }
                if (t.arraySize() == 0) {
                    break;
                }
            }
        }
    }

    void setInts(final int location, final int[] values, final int components, final int count) {
        Slot slot = this.slot(location);
        if (slot == null) {
            return;
        }
        if (slot.sampler) {
            slot.unit = values[0];
            return;
        }
        if (slot.type == null) {
            return;
        }
        this.uniformVersion++;
        GlProgramCompiler.BlockMember t = slot.type;
        int element = location & 0xFF;
        for (int stage = 0; stage < 2; stage++) {
            if (slot.offset[stage] < 0 || this.blocks[stage] == null) {
                continue;
            }
            long base = MemoryUtil.memAddress(this.blocks[stage]);
            int stride = t.arrayStride() > 0 ? t.arrayStride() : 16;
            for (int e = 0; e < count; e++) {
                long at = base + slot.offset[stage] + (long) (element + e) * (t.arraySize() > 0 ? stride : 0);
                if (at + components * 4L > base + this.blocks[stage].capacity()) {
                    break;
                }
                for (int c = 0; c < components; c++) {
                    int v = values[e * components + c];
                    if (GlProgramCompiler.isFloat(t.baseType())) {
                        MemoryUtil.memPutFloat(at + c * 4L, v);
                    } else {
                        MemoryUtil.memPutInt(at + c * 4L, v);
                    }
                }
                if (t.arraySize() == 0) {
                    break;
                }
            }
        }
    }

    /** Column-major matrix data (GL convention), {@code size}×{@code size}. */
    void setMatrix(final int location, final int size, final boolean transpose, final float[] values) {
        Slot slot = this.slot(location);
        if (slot == null || slot.type == null) {
            return;
        }
        this.uniformVersion++;
        GlProgramCompiler.BlockMember t = slot.type;
        int columns = Math.max(1, t.columns());
        int rows = Math.max(1, t.vecSize());
        int matrixStride = t.matrixStride() > 0 ? t.matrixStride() : 16;
        for (int stage = 0; stage < 2; stage++) {
            if (slot.offset[stage] < 0 || this.blocks[stage] == null) {
                continue;
            }
            long at = MemoryUtil.memAddress(this.blocks[stage]) + slot.offset[stage];
            for (int c = 0; c < Math.min(columns, size); c++) {
                for (int r = 0; r < Math.min(rows, size); r++) {
                    float v = transpose ? values[r * size + c] : values[c * size + r];
                    MemoryUtil.memPutFloat(at + (long) c * matrixStride + r * 4L, v);
                }
            }
        }
    }

    /** Named (non-loose) uniform blocks of both stages in a stable order; index = GL uniform block index. */
    final List<String> blockNames = new ArrayList<>();
    /** GL uniform block binding point per block index (-1 = never set). */
    int[] blockBindings = new int[0];

    int blockIndex(final String name) {
        return this.blockNames.indexOf(name);
    }

    void setBlockBinding(final int index, final int binding) {
        if (index >= 0 && index < this.blockBindings.length) {
            this.blockBindings[index] = binding;
        }
    }

    /** MSL buffer index of the block bound to {@code binding} in {@code stage} (0 vertex, 1 fragment), or -1. */
    int bufferIndexForBinding(final int stage, final int binding) {
        if (this.compiled == null) {
            return -1;
        }
        GlProgramCompiler.StageResult s = stage == 0 ? this.compiled.vertex() : this.compiled.fragment();
        for (var e : s.extraBlocks.entrySet()) {
            int index = this.blockNames.indexOf(e.getKey());
            if (index >= 0 && this.blockBindings[index] == binding) {
                return e.getValue();
            }
        }
        return -1;
    }

    int maxFragmentOutput() {
        int max = -1;
        if (this.compiled != null) {
            for (GlProgramCompiler.Interface out : this.compiled.fragment().outputs) {
                max = Math.max(max, out.location());
            }
        }
        return max;
    }

    void freeBlocks() {
        for (int i = 0; i < 2; i++) {
            if (this.blocks[i] != null) {
                MemoryUtil.memFree(this.blocks[i]);
                this.blocks[i] = null;
            }
        }
    }

    Map<String, Long> pipelineCache() {
        return this.pipelines;
    }
}
