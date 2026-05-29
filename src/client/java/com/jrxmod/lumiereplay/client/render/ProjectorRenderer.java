package com.jrxmod.lumiereplay.client.render;

import com.jrxmod.lumiereplay.ModBlocks;
import com.jrxmod.lumiereplay.ProjectorBlock;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders a flat textured quad for each tracked projector.
 * The quad is rotated on the Y axis to match the block's FACING property.
 * When the projector block is broken, VideoManager is notified to stop audio immediately.
 */
public class ProjectorRenderer {

    private static final Map<BlockPos, ScreenTexture> textures = new HashMap<>();
    private static final Set<BlockPos>                knownPos = new HashSet<>();

    public static void register() {
        WorldRenderEvents.LAST.register(ProjectorRenderer::onRenderLast);
    }

    public static void trackProjector(BlockPos pos) {
        BlockPos immutable = pos.toImmutable();
        knownPos.add(immutable);
        textures.computeIfAbsent(immutable, p -> new ScreenTexture());
    }

    public static void untrackProjector(BlockPos pos) {
        knownPos.remove(pos);
        ScreenTexture tex = textures.remove(pos);
        if (tex != null) tex.close();
        // Stop audio and release the player — block is gone
        VideoManager.stopAt(pos);
    }

    private static void onRenderLast(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;

        Vec3d         cam      = ctx.camera().getPos();
        Set<BlockPos> toRemove = new HashSet<>();

        for (BlockPos pos : knownPos) {
            BlockState state = client.world.getBlockState(pos);
            if (state.getBlock() != ModBlocks.PROJECTOR) {
                toRemove.add(pos);
                continue;
            }

            BlockEntity be = client.world.getBlockEntity(pos);
            if (!(be instanceof ProjectorBlockEntity projector)) continue;
            if (projector.getVideoUrl().isEmpty()) continue;

            ScreenTexture tex = textures.computeIfAbsent(pos, p -> new ScreenTexture());
            tex.register();

            Direction facing = state.get(ProjectorBlock.FACING);
            drawScreen(matrices, cam, pos, projector, tex, facing);
        }

        for (BlockPos pos : toRemove) {
            untrackProjector(pos);
        }
    }

    private static void drawScreen(MatrixStack matrices, Vec3d cam,
                                   BlockPos pos, ProjectorBlockEntity projector,
                                   ScreenTexture tex, Direction facing) {
        float w = projector.getScreenWidth();
        float h = projector.getScreenHeight();

        double cx = pos.getX() + 0.5 - cam.x;
        double cy = pos.getY() + 1.0 - cam.y;
        double cz = pos.getZ() + 0.5 - cam.z;

        float yaw = switch (facing) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST  -> 90f;
            case WEST  -> 270f;
            default    -> 0f;
        };

        matrices.push();
        matrices.translate(cx, cy, cz);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
        matrices.translate(0.0, 0.0, -0.502);

        Matrix4f mat = matrices.peek().getPositionMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, tex.register());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);

        Tessellator   tess = Tessellator.getInstance();
        BufferBuilder buf  = tess.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);

        float x0 = -w / 2f;
        float x1 =  w / 2f;
        int   br = 255;

        buf.vertex(mat, x0, h,  0).texture(0, 0).color(br, br, br, 255);
        buf.vertex(mat, x0, 0f, 0).texture(0, 1).color(br, br, br, 255);
        buf.vertex(mat, x1, 0f, 0).texture(1, 1).color(br, br, br, 255);
        buf.vertex(mat, x1, h,  0).texture(1, 0).color(br, br, br, 255);

        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();

        matrices.pop();
    }

    public static void clearAll() {
        textures.values().forEach(ScreenTexture::close);
        textures.clear();
        knownPos.clear();
    }

    public static Map<BlockPos, ScreenTexture> getTextures() { return textures; }
}
