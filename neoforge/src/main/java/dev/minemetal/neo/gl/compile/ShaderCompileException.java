package dev.minemetal.neo.gl.compile;

/** GLSL → MSL translation failure. */
public class ShaderCompileException extends Exception {
    public ShaderCompileException(final String message) {
        super(message);
    }
}
