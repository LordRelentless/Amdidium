package me.lordrelentless.amdidium;

import me.lordrelentless.amdidium.config.AmdidiumConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.util.Util;
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
    public static RenderCapabilities.Platform PLATFORM = RenderCapabilities.Platform.DISABLED;
    public static RenderCapabilities.Capabilities CAPABILITIES = null;

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

        var capabilities = RenderCapabilities.detect();
        CAPABILITIES = capabilities;
        PLATFORM = RenderCapabilities.resolvePlatform(capabilities.vendor, capabilities);

        boolean supported = RenderCapabilities.isCompatible(capabilities);
        IS_COMPATIBLE = supported;

        if (!supported) {
            LOGGER.warn("Amdidium cannot enable on this system: vendor='{}' renderer='{}' version='{}'", capabilities.vendor, capabilities.renderer, capabilities.version);
            IS_ENABLED = false;
            return;
        }

        if (!capabilities.persistentBuffers || !capabilities.sparseBuffers) {
            LOGGER.warn("Sparse/persistent buffer support incomplete; using fallback terrain buffer. Expect increased VRAM usage.");
            SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false;
        }

        Util.OperatingSystem os = Util.getOperatingSystem();
        LOGGER.info("Detected {} GPU platform via vendor '{}' renderer '{}' with meshShaders={}, taskShaders={}, bindlessTextures={}, shaderBufferLoad={}, shaderStorageBuffer={}, persistentBuffers={}, sparseBuffers={}, bufferAddress={}, gl45Compatible={}",
                PLATFORM, capabilities.vendor, capabilities.renderer,
                capabilities.meshShaders, capabilities.taskShaders, capabilities.bindlessTextures,
                capabilities.shaderBufferLoad, capabilities.shaderStorageBuffer,
                capabilities.persistentBuffers, capabilities.sparseBuffers, capabilities.bufferAddress, capabilities.gl45Compatible);

        IS_ENABLED = true;
    }
}
