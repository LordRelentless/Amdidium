package me.lordrelentless.amdidium.gl.shader;

/**
 * Backend-agnostic shader stage enumeration.
 */
public enum ShaderType {
    VERTEX(org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER),
    FRAGMENT(org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER),
    COMPUTE(org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER),
    MESH(org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER),
    TASK(org.lwjgl.opengl.GL43C.GL_COMPUTE_SHADER);

    public final int gl;

    ShaderType(int gl) {
        this.gl = gl;
    }
}
