package me.lordrelentless.amdidium;

import me.lordrelentless.amdidium.config.AmdidiumConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.util.Util;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Amdidium {
    public static final String MOD_VERSION;
    public static final Logger LOGGER = LoggerFactory.getLogger("Amdidium");

    public static boolean IS_COMPATIBLE = false;
    public static boolean IS_ENABLED = false;
    public static boolean IS_DEBUG = System.getProperty("amdidium.isDebug", "false").equals("TRUE");
    public static boolean SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = true;
    public static boolean FORCE_DISABLE = false;

    public static AmdidiumConfig config = AmdidiumConfig.loadOrCreate();

    static {
        ModContainer mod = FabricLoader.getInstance()
                .getModContainer("amdidium")
                .orElseThrow(NullPointerException::new);

        var version = mod.getMetadata().getVersion().getFriendlyString();
        var commit = mod.getMetadata().getCustomValue("commit").getAsString();
        MOD_VERSION = version + "-" + commit;
    }

    public static void checkSystemIsCapable() {
        if (FORCE_DISABLE) {
            LOGGER.warn("Amdidium has been force-disabled via configuration or system property");
            IS_COMPATIBLE = false;
            IS_ENABLED = false;
            return;
        }

        // Ensure a context exists before querying capabilities/vendor
        var cap = GL.getCapabilities();

        // Vendor check: we only enable on AMD GPUs
        String vendor = GL11.glGetString(GL11.GL_VENDOR);
        String renderer = GL11.glGetString(GL11.GL_RENDERER);

        boolean isAMD =
                vendor != null && (
                        vendor.toLowerCase().contains("amd") ||
                        vendor.toLowerCase().contains("advanced micro devices") ||
                        vendor.toLowerCase().contains("ati")
                );

        if (!isAMD) {
            LOGGER.warn("Amdidium is designed for AMD GPUs only. Detected vendor: '{}' (renderer: '{}')", vendor, renderer);
            IS_COMPATIBLE = false;
            IS_ENABLED = false;
            return;
        }

        // --- AMD / ARB / EXT feature checks ---

        // Mesh shaders: prefer standard/vendor-neutral paths when present.
        boolean hasMeshShader =
                cap.GL_ARB_mesh_shader || cap.GL_EXT_mesh_shader;

        // Persistent buffer and sparse buffer support:
        // AMD typically exposes ARB_buffer_storage + ARB_sparse_buffer.
        boolean hasPersistentBuffers =
                cap.GL_ARB_buffer_storage && cap.GL_ARB_sparse_buffer;

        // Multi-draw indirect: check ARB and AMD variants.
        boolean hasMultiDrawIndirect =
                cap.GL_ARB_multi_draw_indirect || cap.GL_AMD_multi_draw_indirect;

        // You can add more AMD-specific goodies here later if Amdidium uses them:
        // e.g. cap.GL_AMD_gpu_shader_int64, cap.GL_AMD_texture_gather_bias_lod, etc.

        boolean supported = hasMeshShader && hasPersistentBuffers && hasMultiDrawIndirect;
        IS_COMPATIBLE = supported;

        if (!supported) {
            LOGGER.warn("Amdidium requirements not met on this AMD GPU:");
            if (!hasMeshShader) {
                LOGGER.warn(" - Missing mesh shader support (ARB_mesh_shader or EXT_mesh_shader)");
            }
            if (!hasPersistentBuffers) {
                LOGGER.warn(" - Missing persistent+sparse buffer support (ARB_buffer_storage + ARB_sparse_buffer)");
            }
            if (!hasMultiDrawIndirect) {
                LOGGER.warn(" - Missing multi-draw indirect support (ARB_multi_draw_indirect or AMD_multi_draw_indirect)");
            }
            LOGGER.warn("Disabling Amdidium");
            IS_ENABLED = false;
            return;
        }

        LOGGER.info("All AMD-targeted OpenGL capabilities required by Amdidium are present");

        // Capability-based fallback: if sparse/persistent buffers are incomplete, drop to a safer path.
        if (!hasPersistentBuffers) {
            LOGGER.warn("Sparse/persistent buffer support incomplete; using fallback terrain buffer. Expect increased VRAM usage.");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        // Optional: OS info is now just informational, not a trigger for fallbacks.
        Util.OperatingSystem os = Util.getOperatingSystem();
        LOGGER.info("Amdidium running on AMD GPU, OS: {}", os.name());

        LOGGER.info("Enabling Amdidium");
        IS_ENABLED = true;
    }
}
