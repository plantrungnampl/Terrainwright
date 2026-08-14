package dev.ssa.fabric.client.spike.preview;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;

final class PreviewRevisionBuffer implements AutoCloseable {
    private static final int VERTICES_PER_BLOCK = 24;

    private final PreviewRevision revision;
    private final StagedVertexBuffer stagedBuffer;
    private StagedVertexBuffer.ExecuteInfo executeInfo;
    private boolean closed;

    PreviewRevisionBuffer(PreviewRevision revision) {
        this.revision = revision;
        int bufferBytes = revision.blockCount()
                * VERTICES_PER_BLOCK
                * DefaultVertexFormat.POSITION_COLOR.getVertexSize();
        this.stagedBuffer = new StagedVertexBuffer(
                () -> "Terrainwright preview revision " + revision.revision(), bufferBytes);
    }

    PreviewRevision revision() {
        return revision;
    }

    StagedVertexBuffer stagedBuffer() {
        if (closed) {
            throw new IllegalStateException("Preview revision buffer is closed");
        }
        return stagedBuffer;
    }

    StagedVertexBuffer.ExecuteInfo executeInfo() {
        return executeInfo;
    }

    void finishBuild(StagedVertexBuffer.Draw draw) {
        stagedBuffer.upload();
        executeInfo = stagedBuffer.getExecuteInfo(draw);
        if (executeInfo == null) {
            throw new IllegalStateException("Preview revision produced no geometry");
        }
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            stagedBuffer.close();
            closed = true;
        }
    }
}
