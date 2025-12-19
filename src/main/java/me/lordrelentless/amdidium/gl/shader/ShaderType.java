package me.lordrelentless.amdidium.gl.shader;

/**
 * Backend-agnostic shader stage enumeration.
 *
 * Each backend (OpenGL, Vulkan, DirectX) maps these stages to its own API:
 *
 *  - OpenGL: GL_VERTEX_SHADER, GL_FRAGMENT_SHADER, etc.
 *  - Vulkan: VkShaderStageFlagBits
 *  - DirectX: D3D12_SHADER_VISIBILITY / pipeline stage slots
 *
 * Mesh and task shaders are included for Vulkan/DX12 and for OpenGL ARB_mesh_shader.
 */
public enum ShaderType {
    VERTEX,
    FRAGMENT,
    COMPUTE,
    MESH,
    TASK;
}
