package me.lordrelentless.amdidium.mixin.minecraft;

import me.lordrelentless.amdidium.Amdidium;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "getFarPlaneDistance", at = @At("HEAD"), cancellable = true)
    public void method_32796(CallbackInfoReturnable<Float> cir) {
        if (Amdidium.IS_ENABLED) {
            cir.setReturnValue(16 * 512f);
            cir.cancel();
        }
    }
}
