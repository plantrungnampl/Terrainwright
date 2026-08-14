package dev.ssa.fabric.client.spike.preview;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class GhostPreviewRenderer {
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f IDENTITY_MATRIX = new Matrix4f();
    private static final Matrix4f PREVIEW_MODEL_VIEW = new Matrix4f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final PreviewRenderMetrics METRICS = new PreviewRenderMetrics();

    private static boolean initialized;
    private static PreviewRevisionBuffer activeBuffer;
    private static PreviewRevisionBuffer extractedBuffer;
    private static volatile int createdBuffers;
    private static volatile int closedBuffers;
    private static volatile int renderedFrames;

    private GhostPreviewRenderer() {}

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        LevelExtractionEvents.END_EXTRACTION.register(GhostPreviewRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(GhostPreviewRenderer::renderAndDraw);
    }

    public static void replace(PreviewRevision revision) {
        closeActiveBuffer();
        activeBuffer = new PreviewRevisionBuffer(revision);
        createdBuffers++;
    }

    public static void dispose() {
        closeActiveBuffer();
        extractedBuffer = null;
    }

    public static void startMetrics() {
        METRICS.start();
    }

    public static PreviewRenderMetrics.Profile stopMetrics() {
        return METRICS.stop();
    }

    public static int metricSampleCount() {
        return METRICS.sampleCount();
    }

    public static long activeRevision() {
        return activeBuffer == null ? -1 : activeBuffer.revision().revision();
    }

    public static int createdBufferCount() {
        return createdBuffers;
    }

    public static int closedBufferCount() {
        return closedBuffers;
    }

    public static int liveBufferCount() {
        return activeBuffer == null ? 0 : 1;
    }

    public static int renderedFrameCount() {
        return renderedFrames;
    }

    private static void closeActiveBuffer() {
        if (activeBuffer != null) {
            activeBuffer.close();
            closedBuffers++;
            activeBuffer = null;
        }
    }

    private static void extract(LevelExtractionContext context) {
        extractedBuffer = activeBuffer;
    }

    private static void renderAndDraw(LevelRenderContext context) {
        PreviewRevisionBuffer revisionBuffer = extractedBuffer;
        if (revisionBuffer == null || revisionBuffer != activeBuffer || revisionBuffer.closed()) {
            return;
        }

        long allocatedBefore = METRICS.collecting() ? METRICS.currentThreadAllocatedBytes() : 0;
        long started = System.nanoTime();
        RenderPipeline pipeline = RenderPipelines.DEBUG_FILLED_BOX;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        if (format == null) {
            throw new IllegalStateException("Preview render pipeline has no vertex binding");
        }

        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        if (revisionBuffer.executeInfo() == null) {
            StagedVertexBuffer stagedBuffer = revisionBuffer.stagedBuffer();
            StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(format, primitive);
            buildRevisionGeometry(stagedBuffer.getVertexBuilder(draw), revisionBuffer.revision());
            revisionBuffer.finishBuild(draw);
        }
        draw(Minecraft.getInstance(), revisionBuffer.executeInfo(), pipeline, revisionBuffer.revision(), context);
        renderedFrames++;
        METRICS.record(System.nanoTime() - started, allocatedBefore);
    }

    private static void buildRevisionGeometry(VertexConsumer vertices, PreviewRevision revision) {
        for (int index = 0; index < revision.blockCount(); index++) {
            float x = revision.xAt(index);
            float y = revision.yAt(index);
            float z = revision.zAt(index);
            PreviewLayer layer = revision.layerAt(index);
            renderFilledBox(
                    IDENTITY_MATRIX,
                    vertices,
                    x,
                    y,
                    z,
                    x + 0.96f,
                    y + 0.96f,
                    z + 0.96f,
                    layer.red(),
                    layer.green(),
                    layer.blue(),
                    layer.alpha());
        }
    }

    private static void renderFilledBox(
            Matrix4fc matrix,
            VertexConsumer vertices,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ,
            float red,
            float green,
            float blue,
            float alpha) {
        vertex(vertices, matrix, minX, minY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, minY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, maxY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, minZ, red, green, blue, alpha);
        vertex(vertices, matrix, maxX, minY, maxZ, red, green, blue, alpha);
        vertex(vertices, matrix, minX, minY, maxZ, red, green, blue, alpha);
    }

    private static void vertex(
            VertexConsumer vertices,
            Matrix4fc matrix,
            float x,
            float y,
            float z,
            float red,
            float green,
            float blue,
            float alpha) {
        vertices.addVertex(matrix, x, y, z).setColor(red, green, blue, alpha);
    }

    private static void draw(
            Minecraft client,
            StagedVertexBuffer.ExecuteInfo info,
            RenderPipeline pipeline,
            PreviewRevision revision,
            LevelRenderContext context) {
        var camera = context.levelState().cameraRenderState.pos;
        PREVIEW_MODEL_VIEW
                .set(RenderSystem.getModelViewMatrixCopy())
                .mul(context.poseStack().last().pose())
                .translate(
                        (float) (revision.originX() - camera.x),
                        (float) (revision.originY() - camera.y),
                        (float) (revision.originZ() - camera.z));
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                PREVIEW_MODEL_VIEW, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) {
            return;
        }

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Terrainwright ghost preview",
                        colorTexture,
                        Optional.empty(),
                        mainTarget.getDepthTextureView(),
                        OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }
}
