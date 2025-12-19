package me.lordrelentless.amdidium.mixin.sodium;

import me.lordrelentless.amdidium.sodiumCompat.IRepackagedResult;
import me.lordrelentless.amdidium.sodiumCompat.RepackagedSectionOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChunkBuildOutput.class, remap = false)
public class MixinChunkBuildOutput implements IRepackagedResult {

    @Unique
    private RepackagedSectionOutput repackagedSectionOutput;

    @Override
    public RepackagedSectionOutput getOutput() {
        return repackagedSectionOutput;
    }

    @Override
    public void set(RepackagedSectionOutput output) {
        repackagedSectionOutput = output;
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void cleanup(CallbackInfo ci) {
        if (repackagedSectionOutput != null) {
            repackagedSectionOutput.delete();
        }
    }
}
