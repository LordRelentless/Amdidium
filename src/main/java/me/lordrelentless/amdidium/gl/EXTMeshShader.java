package me.lordrelentless.amdidium.gl;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.JNI;

public final class EXTMeshShader {
    public static final int GL_MESH_SHADER_EXT = 0x9559;
    public static final int GL_TASK_SHADER_EXT = 0x955A;

    private static final long DRAW_MESH_TASKS;
    private static final long MULTI_DRAW_MESH_TASKS_INDIRECT;

    static {
        if (GL.getFunctionProvider() == null) {
            throw new IllegalStateException("EXT mesh shader entry points require an active OpenGL context");
        }
        DRAW_MESH_TASKS = GL.getFunctionProvider().getFunctionAddress("glDrawMeshTasksEXT");
        MULTI_DRAW_MESH_TASKS_INDIRECT = GL.getFunctionProvider().getFunctionAddress("glMultiDrawMeshTasksIndirectEXT");
    }

    private EXTMeshShader() {
    }

    public static void glDrawMeshTasksEXT(int groupCountX, int groupCountY, int groupCountZ) {
        if (DRAW_MESH_TASKS == 0) {
            throw new IllegalStateException("glDrawMeshTasksEXT is not supported by this OpenGL context");
        }
        JNI.invokeI(groupCountX, groupCountY, groupCountZ, DRAW_MESH_TASKS);
    }

    public static void glMultiDrawMeshTasksIndirectEXT(long indirectOffset, int drawCount, int stride) {
        if (MULTI_DRAW_MESH_TASKS_INDIRECT == 0) {
            throw new IllegalStateException("glMultiDrawMeshTasksIndirectEXT is not supported by this OpenGL context");
        }
        JNI.invokePI(indirectOffset, drawCount, stride, MULTI_DRAW_MESH_TASKS_INDIRECT);
    }
}