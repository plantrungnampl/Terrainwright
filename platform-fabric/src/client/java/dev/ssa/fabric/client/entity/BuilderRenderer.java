package dev.ssa.fabric.client.entity;

import dev.ssa.fabric.entity.BuilderEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

public final class BuilderRenderer extends HumanoidMobRenderer<
        BuilderEntity,
        HumanoidRenderState,
        HumanoidModel<HumanoidRenderState>> {
    private static final Identifier TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public BuilderRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return TEXTURE;
    }
}
