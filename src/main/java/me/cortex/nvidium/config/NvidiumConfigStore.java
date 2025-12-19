package me.cortex.amdidium.config;

import me.cortex.amdidium.Amdidium;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

public class AmdidiumConfigStore implements OptionStorage<AmdidiumConfig> {

    private final AmdidiumConfig config;

    public AmdidiumConfigStore() {
        this.config = Amdidium.config;
    }

    @Override
    public AmdidiumConfig getData() {
        return config;
    }

    @Override
    public void save() {
        config.save();
    }
}
