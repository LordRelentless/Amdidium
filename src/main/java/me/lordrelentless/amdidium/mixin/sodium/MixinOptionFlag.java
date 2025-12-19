package me.lordrelentless.amdidium.mixin.sodium;

import me.lordrelentless.amdidium.sodiumCompat.AmdidiumOptionFlags;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = OptionFlag.class, remap = false)
public class MixinOptionFlag {

    @Shadow
    @Final
    @Mutable
    private static OptionFlag[] $VALUES =
            ArrayUtils.addAll(MixinOptionFlag.$VALUES, AmdidiumOptionFlags.REQUIRES_SHADER_RELOAD);

    public MixinOptionFlag() {
    }

    @Invoker("<init>")
    public static OptionFlag optionFlagCreator(String internalName, int internalId) {
        throw new AssertionError();
    }

    static {
        AmdidiumOptionFlags.REQUIRES_SHADER_RELOAD =
                optionFlagCreator("REQUIRES_SHADER_RELOAD", $VALUES.length);
    }
}
