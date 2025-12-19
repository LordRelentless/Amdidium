package me.lordrelentless.amdidium.api0;

import me.lordrelentless.amdidium.Amdidium;
import me.lordrelentless.amdidium.sodiumCompat.IAmdidiumWorldRendererGetter;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import org.joml.Matrix4fc;

public class AmdidiumAPI {
    private final String modName;

    public AmdidiumAPI(String modName) {
        this.modName = modName;
    }

    /**
     * Forces a render section to not render, guarantees the section will stay hidden
     * until it is marked as visible.
     *
     * @param x section X position
     * @param y section Y position
     * @param z section Z position
     */
    public void hideSection(int x, int y, int z) {
        if (Amdidium.IS_ENABLED) {
            var renderer = ((IAmdidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            if (renderer != null) {
                renderer.getSectionManager().setHideBit(x, y, z, true);
            }
        }
    }

    /**
     * Unhides a render section if it was previously hidden.
     *
     * @param x section X position
     * @param y section Y position
     * @param z section Z position
     */
    public void showSection(int x, int y, int z) {
        if (Amdidium.IS_ENABLED) {
            var renderer = ((IAmdidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            if (renderer != null) {
                renderer.getSectionManager().setHideBit(x, y, z, false);
            }
        }
    }

    /**
     * Assigns a specified region to the supplied transformation id.
     *
     * @param id id to set the region to (all regions have the default id of 0)
     * @param x  region X position
     * @param y  region Y position
     * @param z  region Z position
     */
    public void setRegionTransformId(int id, int x, int y, int z) {
        if (Amdidium.IS_ENABLED) {
            var renderer = ((IAmdidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            if (renderer != null) {
                renderer.getSectionManager()
                        .getRegionManager()
                        .setRegionTransformId(x, y, z, id);
            }
        }
    }

    /**
     * Sets the transform for the supplied id.
     *
     * @param id        The id to set the transform of
     * @param transform The transform to set it to
     */
    public void setTransformation(int id, Matrix4fc transform) {
        if (Amdidium.IS_ENABLED) {
            var renderer = ((IAmdidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            if (renderer != null) {
                renderer.setTransformation(id, transform);
            }
        }
    }

    /**
     * Sets the origin point of the transformation id, in chunk coordinates.
     *
     * @param id The id to set the origin of
     * @param x  Chunk coord x
     * @param y  Chunk coord y
     * @param z  Chunk coord z
     */
    public void setOrigin(int id, int x, int y, int z) {
        if (Amdidium.IS_ENABLED) {
            var renderer = ((IAmdidiumWorldRendererGetter) SodiumWorldRenderer.instance()).getRenderer();
            if (renderer != null) {
                renderer.setOrigin(id, x, y, z);
            }
        }
    }
}
