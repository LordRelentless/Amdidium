package me.cortex.amdidium.config;

import com.google.common.collect.ImmutableList;
import me.cortex.amdidium.Amdidium;
import me.cortex.amdidium.sodiumCompat.AmdidiumOptionFlags;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ConfigGuiBuilder {

    private static final AmdidiumConfigStore store = new AmdidiumConfigStore();

    public static void addAmdidiumGui(List<OptionPage> pages) {
        List<OptionGroup> groups = new ArrayList<>();

        // ───────────────────────────────────────────────────────────────
        //  Disable Amdidium (temporary toggle)
        // ───────────────────────────────────────────────────────────────
        groups.add(OptionGroup.createBuilder()
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.literal("Disable Amdidium"))
                        .setTooltip(Text.literal("Temporarily disables Amdidium (does NOT persist across relaunches)."))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> Amdidium.FORCE_DISABLE = value,
                                    opts -> Amdidium.FORCE_DISABLE)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                ).build()
        );

        // ───────────────────────────────────────────────────────────────
        //  Disabled due to shaders (informational)
        // ───────────────────────────────────────────────────────────────
        if (Amdidium.IS_COMPATIBLE && !Amdidium.IS_ENABLED && !Amdidium.FORCE_DISABLE) {
            groups.add(OptionGroup.createBuilder()
                    .add(OptionImpl.createBuilder(boolean.class, store)
                            .setName(Text.literal("Amdidium disabled due to shaders being loaded"))
                            .setTooltip(Text.literal("Amdidium is disabled because external shaders are active."))
                            .setControl(TickBoxControl::new)
                            .setImpact(OptionImpact.VARIES)
                            .setBinding((opts, value) -> {}, opts -> false)
                            .setFlags()
                            .build()
                    ).build()
            );
        }

        // ───────────────────────────────────────────────────────────────
        //  Main Amdidium Options
        // ───────────────────────────────────────────────────────────────
        groups.add(OptionGroup.createBuilder()

                // Region Keep Distance
                .add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("amdidium.options.region_keep_distance.name"))
                        .setTooltip(Text.translatable("amdidium.options.region_keep_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 32, 256, 1,
                                x -> Text.literal(x == 32 ? "Vanilla" :
                                        (x == 256 ? "Keep All" : x + " chunks"))))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.region_keep_distance = value,
                                    opts -> opts.region_keep_distance)
                        .build()
                )

                // Temporal Coherence
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("amdidium.options.enable_temporal_coherence.name"))
                        .setTooltip(Text.translatable("amdidium.options.enable_temporal_coherence.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.enable_temporal_coherence = value,
                                    opts -> opts.enable_temporal_coherence)
                        .build()
                )

                // Async BFS
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("amdidium.options.async_bfs.name"))
                        .setTooltip(Text.translatable("amdidium.options.async_bfs.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.async_bfs = value,
                                    opts -> opts.async_bfs)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )

                // Automatic Memory
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("amdidium.options.automatic_memory_limit.name"))
                        .setTooltip(Text.translatable("amdidium.options.automatic_memory_limit.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setBinding((opts, value) -> opts.automatic_memory = value,
                                    opts -> opts.automatic_memory)
                        .build()
                )

                // Max GPU Memory
                .add(OptionImpl.createBuilder(int.class, store)
                        .setName(Text.translatable("amdidium.options.max_gpu_memory.name"))
                        .setTooltip(Text.translatable("amdidium.options.max_gpu_memory.tooltip"))
                        .setControl(option -> new SliderControl(option, 2048, 32768, 512,
                                ControlValueFormatter.translateVariable("amdidium.options.mb")))
                        .setImpact(OptionImpact.VARIES)
                        .setEnabled(Amdidium.IS_ENABLED && !Amdidium.config.automatic_memory)
                        .setBinding((opts, value) -> opts.max_geometry_memory = value,
                                    opts -> opts.max_geometry_memory)
                        .setFlags(Amdidium.SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER
                                ? new OptionFlag[0]
                                : new OptionFlag[]{OptionFlag.REQUIRES_RENDERER_RELOAD})
                        .build()
                )

                // Fog Rendering
                .add(OptionImpl.createBuilder(boolean.class, store)
                        .setName(Text.translatable("amdidium.options.render_fog.name"))
                        .setTooltip(Text.translatable("amdidium.options.render_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.render_fog = value,
                                    opts -> opts.render_fog)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )

                // Translucency Sorting
                .add(OptionImpl.createBuilder(TranslucencySortingLevel.class, store)
                        .setName(Text.translatable("amdidium.options.translucency_sorting.name"))
                        .setTooltip(Text.translatable("amdidium.options.translucency_sorting.tooltip"))
                        .setControl(opts -> new CyclingControl<>(
                                opts,
                                TranslucencySortingLevel.class,
                                new Text[]{
                                        Text.translatable("amdidium.options.translucency_sorting.none"),
                                        Text.translatable("amdidium.options.translucency_sorting.sections"),
                                        Text.translatable("amdidium.options.translucency_sorting.quads")
                                }))
                        .setBinding((opts, value) -> opts.translucency_sorting_level = value,
                                    opts -> opts.translucency_sorting_level)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )

                // Statistics Logging
                .add(OptionImpl.createBuilder(StatisticsLoggingLevel.class, store)
                        .setName(Text.translatable("amdidium.options.statistics_level.name"))
                        .setTooltip(Text.translatable("amdidium.options.statistics_level.tooltip"))
                        .setControl(opts -> new CyclingControl<>(
                                opts,
                                StatisticsLoggingLevel.class,
                                new Text[]{
                                        Text.translatable("amdidium.options.statistics_level.none"),
                                        Text.translatable("amdidium.options.statistics_level.frustum"),
                                        Text.translatable("amdidium.options.statistics_level.regions"),
                                        Text.translatable("amdidium.options.statistics_level.sections"),
                                        Text.translatable("amdidium.options.statistics_level.quads")
                                }))
                        .setBinding((opts, value) -> opts.statistics_level = value,
                                    opts -> opts.statistics_level)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(AmdidiumOptionFlags.REQUIRES_SHADER_RELOAD)
                        .build()
                )

                // ───────────────────────────────────────────────────────────────
                // Backend Selection (NEW)
                // ───────────────────────────────────────────────────────────────
                .add(OptionImpl.createBuilder(AmdidiumConfig.Backend.class, store)
                        .setName(Text.literal("Rendering Backend"))
                        .setTooltip(Text.literal("Choose between OpenGL, Vulkan, or DirectX backends."))
                        .setControl(opts -> new CyclingControl<>(
                                opts,
                                AmdidiumConfig.Backend.class,
                                new Text[]{
                                        Text.literal("OpenGL"),
                                        Text.literal("Vulkan"),
                                        Text.literal("DirectX")
                                }))
                        .setBinding((opts, value) -> opts.backend = value,
                                    opts -> opts.backend)
                        .setEnabled(Amdidium.IS_ENABLED)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build()
                )

                .build()
        );

        if (Amdidium.IS_COMPATIBLE) {
            pages.add(new OptionPage(
                    Text.translatable("amdidium.options.pages.amdidium"),
                    ImmutableList.copyOf(groups)
            ));
        }
    }
}
