package me.lordrelentless.amdidium.mixin.sodium;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.AmdidiumWorldRenderer;
import me.lordrelentless.amdidium.managers.AsyncOcclusionTracker;
import me.lordrelentless.amdidium.sodiumCompat.*;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.render.texture.SpriteUtil;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager implements IAmdidiumWorldRendererGetter {

    @Shadow @Final private RenderRegionManager regions;
    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;
    @Shadow private @NotNull Map<ChunkUpdateType, ArrayDeque<RenderSection>> rebuildLists;
    @Shadow @Final private int renderDistance;

    @Unique private AmdidiumWorldRenderer renderer;
    @Unique private Viewport viewport;

    @Unique
    private static void updateAmdidiumIsEnabled() {
        Amdidium.IS_ENABLED =
                (!Amdidium.FORCE_DISABLE)
                && Amdidium.IS_COMPATIBLE
                && IrisCheck.checkIrisShouldDisable();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void init(ClientWorld world, int renderDistance, CommandList commandList, CallbackInfo ci) {
        updateAmdidiumIsEnabled();
        if (Amdidium.IS_ENABLED) {
            if (renderer != null)
                throw new IllegalStateException("Cannot have multiple world renderers");

            renderer = new AmdidiumWorldRenderer(
                    Amdidium.config.async_bfs
                            ? new AsyncOcclusionTracker(renderDistance, sectionByPosition, world, rebuildLists)
                            : null
            );

            ((IAmdidiumWorldRendererSetter) regions).setWorldRenderer(renderer);
        }
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>(Lnet/minecraft/client/world/ClientWorld;Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V",
                    remap = true
            ),
            index = 1
    )
    private ChunkVertexType modifyVertexType(ChunkVertexType vertexType) {
        updateAmdidiumIsEnabled();
        if (Amdidium.IS_ENABLED) {
            return AmdidiumCompactChunkVertex.INSTANCE;
        }
        return vertexType;
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void destroy(CallbackInfo ci) {
        if (Amdidium.IS_ENABLED) {
            if (renderer == null)
                throw new IllegalStateException("Pipeline already destroyed");

            ((IAmdidiumWorldRendererSetter) regions).setWorldRenderer(null);
            renderer.delete();
            renderer = null;
        }
    }

    @Redirect(
            method = "onSectionRemoved",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;delete()V"
            )
    )
    private void deleteSection(RenderSection section) {
        if (Amdidium.IS_ENABLED) {
            if (Amdidium.config.region_keep_distance == 32) {
                renderer.deleteSection(section);
            }
        }
        section.delete();
    }

    @Inject(method = "update", at = @At("HEAD"))
    private void trackViewport(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        this.viewport = viewport;
    }

    @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true)
    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z, CallbackInfo ci) {
        if (Amdidium.IS_ENABLED) {
            ci.cancel();
            pass.startDrawing();

            if (pass == DefaultTerrainRenderPasses.SOLID) {
                renderer.renderFrame(viewport, matrices, x, y, z);
            } else if (pass == DefaultTerrainRenderPasses.TRANSLUCENT) {
                renderer.renderTranslucent();
            }

            pass.endDrawing();
        }
    }

    @Inject(method = "getDebugStrings", at = @At("HEAD"), cancellable = true)
    private void redirectDebug(CallbackInfoReturnable<Collection<String>> cir) {
        if (Amdidium.IS_ENABLED) {
            var debugStrings = new ArrayList<String>();
            renderer.addDebugInfo(debugStrings);
            cir.setReturnValue(debugStrings);
            cir.cancel();
        }
    }

    @Override
    public AmdidiumWorldRenderer getRenderer() {
        return renderer;
    }

    @Inject(method = "createTerrainRenderList", at = @At("HEAD"), cancellable = true)
    private void redirectTerrainRenderList(Camera camera, Viewport viewport, int frame, boolean spectator, CallbackInfo ci) {
        if (Amdidium.IS_ENABLED && Amdidium.config.async_bfs) {
            ci.cancel();
        }
    }

    @Redirect(
            method = "submitRebuildTasks",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lme/jellysquid/mods/sodium/client/render/chunk/ChunkUpdateType;)V"
            )
    )
    private void injectEnqueueFalse(RenderSection instance, ChunkUpdateType type) {
        instance.setPendingUpdate(type);

        if (Amdidium.IS_ENABLED && Amdidium.config.async_bfs) {
            ((IRenderSectionExtension) instance).isSubmittedRebuild(false);
        }
    }

    @Unique
    private boolean isSectionVisibleBfs(RenderSection section) {
        int delta = Math.abs(section.getLastVisibleFrame() - renderer.getAsyncFrameId());
        return delta <= 1;
    }

    @Inject(
            method = "isSectionVisible",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;getLastVisibleFrame()I",
                    shift = At.Shift.BEFORE
            ),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void redirectIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir, RenderSection render) {
        if (Amdidium.IS_ENABLED && Amdidium.config.async_bfs) {
            cir.setReturnValue(isSectionVisibleBfs(render));
        }
    }

    @Inject(method = "tickVisibleRenders", at = @At("HEAD"), cancellable = true)
    private void redirectAnimatedSpriteUpdates(CallbackInfo ci) {
        if (Amdidium.IS_ENABLED
                && Amdidium.config.async_bfs
                && SodiumClientMod.options().performance.animateOnlyVisibleTextures) {

            ci.cancel();
            var sprites = renderer.getAnimatedSpriteSet();
            if (sprites == null) return;

            for (var sprite : sprites) {
                SpriteUtil.markSpriteActive(sprite);
            }
        }
    }

    @Inject(
            method = "scheduleRebuild",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;setPendingUpdate(Lme/jellysquid/mods/sodium/client/render/chunk/ChunkUpdateType;)V",
                    shift = At.Shift.AFTER
            ),
            locals = LocalCapture.CAPTURE_FAILHARD
    )
    private void instantReschedule(int x, int y, int z, boolean important, CallbackInfo ci, RenderSection section, ChunkUpdateType pendingUpdate) {
        if (Amdidium.IS_ENABLED && Amdidium.config.async_bfs) {
            var queue = rebuildLists.get(pendingUpdate);

            if (isSectionVisibleBfs(section) && queue.size() < pendingUpdate.getMaximumQueueSize()) {
                ((IRenderSectionExtension) section).isSubmittedRebuild(true);
                rebuildLists.get(pendingUpdate).add(section);
            }
        }
    }

    @Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
    private void injectVisibilityCount(CallbackInfoReturnable<Integer> cir) {
        if (Amdidium.IS_ENABLED && Amdidium.config.async_bfs) {
            cir.setReturnValue(this.renderer.getAsyncBfsVisibilityCount());
        }
    }
}
