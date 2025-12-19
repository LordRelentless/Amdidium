package me.cortex.amdidium.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.cortex.amdidium.Amdidium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

public class AmdidiumConfig {

    // ───────────────────────────────────────────────────────────────
    //  Rendering Backend (NEW for Option B2)
    // ───────────────────────────────────────────────────────────────
    public enum Backend {
        OPENGL,
        VULKAN,
        DIRECTX
    }

    // ───────────────────────────────────────────────────────────────
    //  Configurable Options (ported from Nvidium)
    // ───────────────────────────────────────────────────────────────
    public int extra_rd = 100;
    public boolean enable_temporal_coherence = true;

    // Max geometry memory in MB
    public int max_geometry_memory = 2048;
    public boolean automatic_memory = true;

    public boolean async_bfs = true;

    public int region_keep_distance = 32;

    public boolean render_fog = true;

    public TranslucencySortingLevel translucency_sorting_level =
            TranslucencySortingLevel.QUADS;

    public StatisticsLoggingLevel statistics_level =
            StatisticsLoggingLevel.NONE;

    // NEW: backend selection
    public Backend backend = Backend.OPENGL;

    // ───────────────────────────────────────────────────────────────
    //  JSON Serialization
    // ───────────────────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    private AmdidiumConfig() {}

    public static AmdidiumConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                return GSON.fromJson(reader, AmdidiumConfig.class);
            } catch (IOException e) {
                Amdidium.LOGGER.error("Could not parse Amdidium config", e);
            }
        }
        return new AmdidiumConfig();
    }

    public void save() {
        // TODO: make atomic write
        try {
            Files.writeString(getConfigPath(), GSON.toJson(this));
        } catch (IOException e) {
            Amdidium.LOGGER.error("Failed to write Amdidium config file", e);
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("amdidium-config.json");
    }
}
