package dev.blockesp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Block ESP: draws translucent colored boxes through walls on chests, barrels and hoppers.
 *
 * Everything that touches Minecraft's low-level renderer is in {@link #draw(WorldRenderContext)}.
 * If a future version changes the render API, that is the only method that needs fixing.
 */
public final class BlockEsp {

    public static boolean enabled = true;
    public static boolean chests = true;
    public static boolean barrels = true;
    public static boolean hoppers = true;
    /** Max distance in blocks (8-700). Only chunks your game has loaded can show anything. */
    public static int rangeBlocks = 700;

    private static final float ALPHA = 0.40f;

    private record Target(double x, double y, double z, float r, float g, float b) {}

    private static volatile List<Target> targets = List.of();
    private static int tickCounter = 0;
    private static boolean renderFailed = false;

    /** Filled boxes, no depth test (so they show through walls). */
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("blockesp", "pipeline/esp_filled"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build()
    );

    private BlockEsp() {}

    public static void init() {
        // Look for storage blocks twice a second.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++tickCounter % 10 != 0) return;
            rescan(mc);
        });

        WorldRenderEvents.BEFORE_TRANSLUCENT.register(BlockEsp::draw);
    }

    // ---------------------------------------------------------------- scanning

    private static void rescan(Minecraft mc) {
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled || player == null || level == null) {
            targets = List.of();
            return;
        }

        int pcx = Mth.floor(player.getX()) >> 4;
        int pcz = Mth.floor(player.getZ()) >> 4;
        List<Target> found = new ArrayList<>();

        int radiusChunks = (rangeBlocks >> 4) + 1;
        double maxSq = (double) rangeBlocks * rangeBlocks;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();

        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
                if (chunk == null) continue;

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    Target t = classify(be);
                    if (t == null) continue;

                    double ddx = t.x + 0.5 - px;
                    double ddy = t.y + 0.5 - py;
                    double ddz = t.z + 0.5 - pz;
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= maxSq) found.add(t);
                }
            }
        }
        targets = found;
    }

    private static Target classify(BlockEntity be) {
        double x = be.getBlockPos().getX();
        double y = be.getBlockPos().getY();
        double z = be.getBlockPos().getZ();

        if (chests && be instanceof ChestBlockEntity) {
            return new Target(x, y, z, 1.00f, 0.65f, 0.05f); // gold
        }
        if (barrels && be instanceof BarrelBlockEntity) {
            return new Target(x, y, z, 0.15f, 0.80f, 1.00f); // cyan
        }
        if (hoppers && be instanceof HopperBlockEntity) {
            return new Target(x, y, z, 0.70f, 0.30f, 1.00f); // purple
        }
        return null;
    }

    // ---------------------------------------------------------------- drawing

    private static void draw(WorldRenderContext context) {
        if (!enabled || renderFailed) return;

        List<Target> list = targets;
        if (list.isEmpty()) return;

        try {
            Vec3 cam = context.worldState().cameraRenderState.pos;
            PoseStack matrices = context.matrices();

            matrices.pushPose();
            matrices.translate(-cam.x, -cam.y, -cam.z);
            Matrix4f pose = new Matrix4f(matrices.last().pose());
            matrices.popPose();

            try (ByteBufferBuilder allocator = new ByteBufferBuilder(Math.max(256, list.size() * 24 * 16))) {
                BufferBuilder buffer = new BufferBuilder(allocator, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

                for (Target t : list) {
                    addBox(buffer, pose,
                        (float) t.x, (float) t.y, (float) t.z,
                        (float) t.x + 1f, (float) t.y + 1f, (float) t.z + 1f,
                        t.r, t.g, t.b, ALPHA);
                }

                try (MeshData mesh = buffer.buildOrThrow()) {
                    int indexCount = mesh.drawState().indexCount();
                    if (indexCount == 0) return;

                    GpuBuffer vertexBuffer = DefaultVertexFormat.POSITION_COLOR.uploadImmediateVertexBuffer(mesh.vertexBuffer());

                    RenderSystem.AutoStorageIndexBuffer sequential = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
                    GpuBuffer indexBuffer = sequential.getBuffer(indexCount);

                    GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                        RenderSystem.getModelViewMatrix(),
                        new Vector4f(1f, 1f, 1f, 1f),
                        new Vector3f(),
                        RenderSystem.getTextureMatrix()
                    );

                    RenderTarget target = Minecraft.getInstance().getMainRenderTarget();

                    try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                        () -> "blockesp",
                        target.getColorTextureView(),
                        OptionalInt.empty()
                    )) {
                        pass.setPipeline(PIPELINE);
                        RenderSystem.bindDefaultUniforms(pass);
                        pass.setUniform("DynamicTransforms", transforms);
                        pass.setVertexBuffer(0, vertexBuffer);
                        pass.setIndexBuffer(indexBuffer, sequential.type());
                        pass.drawIndexed(0, 0, indexCount, 1);
                    }
                }
            }
        } catch (Throwable t) {
            // Never crash the game because of the overlay: switch it off and say why.
            renderFailed = true;
            System.err.println("[blockesp] Block ESP rendering disabled after error:");
            t.printStackTrace();
        }
    }

    private static void addBox(BufferBuilder b, Matrix4f m,
                               float x1, float y1, float z1, float x2, float y2, float z2,
                               float r, float g, float bl, float a) {
        // bottom
        v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x2, y1, z1, r, g, bl, a);
        v(b, m, x2, y1, z2, r, g, bl, a); v(b, m, x1, y1, z2, r, g, bl, a);
        // top
        v(b, m, x1, y2, z1, r, g, bl, a); v(b, m, x1, y2, z2, r, g, bl, a);
        v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x2, y2, z1, r, g, bl, a);
        // north
        v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x1, y2, z1, r, g, bl, a);
        v(b, m, x2, y2, z1, r, g, bl, a); v(b, m, x2, y1, z1, r, g, bl, a);
        // south
        v(b, m, x1, y1, z2, r, g, bl, a); v(b, m, x2, y1, z2, r, g, bl, a);
        v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x1, y2, z2, r, g, bl, a);
        // west
        v(b, m, x1, y1, z1, r, g, bl, a); v(b, m, x1, y1, z2, r, g, bl, a);
        v(b, m, x1, y2, z2, r, g, bl, a); v(b, m, x1, y2, z1, r, g, bl, a);
        // east
        v(b, m, x2, y1, z1, r, g, bl, a); v(b, m, x2, y2, z1, r, g, bl, a);
        v(b, m, x2, y2, z2, r, g, bl, a); v(b, m, x2, y1, z2, r, g, bl, a);
    }

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, float z,
                          float r, float g, float bl, float a) {
        b.addVertex(m, x, y, z).setColor(r, g, bl, a);
    }
}
