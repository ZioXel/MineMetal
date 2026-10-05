package dev.minemetal.client.glcompat;

/** Emulated shader object; sources are compiled together at glLinkProgram time. */
final class GlShaderObject {
    final int id;
    final int type;
    String source = "";

    GlShaderObject(final int id, final int type) {
        this.id = id;
        this.type = type;
    }
}
