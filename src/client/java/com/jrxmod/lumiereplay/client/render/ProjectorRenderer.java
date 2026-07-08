package com.jrxmod.lumiereplay.client.render;

import com.jrxmod.lumiereplay.ModBlocks;
import com.jrxmod.lumiereplay.ProjectorBlock;
import com.jrxmod.lumiereplay.ProjectorBlockEntity;
import com.jrxmod.lumiereplay.client.LumiereConfig;
import com.jrxmod.lumiereplay.client.video.VideoManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders video on the front face of each projector block.
 * Back-face culling is enabled so the screen quad is visible only from the front.
 * The back face of the cube model is a solid texture (projector_back.png).
 */
public class ProjectorRenderer {

    private static final Map<BlockPos, ScreenTexture> textures = new HashMap<>();
    private static final Set<BlockPos> knownPos = new HashSet<>();

    public static void register() {
        WorldRenderEvents.LAST.register(ProjectorRenderer::onRenderLast);
    }

    public static void trackProjector(BlockPos pos) {
        BlockPos immutable = pos.toImmutable();
        synchronized (textures) {
            knownPos.add(immutable);
            textures.computeIfAbsent(immutable, p -> new ScreenTexture());
        }
    }

    public static void untrackProjector(BlockPos pos) {
        ScreenTexture tex;
        synchronized (textures) { knownPos.remove(pos); tex = textures.remove(pos); }
        if (tex != null) tex.close();
        VideoManager.stopAt(pos);
    }

    private static void onRenderLast(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;
        MatrixStack matrices = ctx.matrixStack();
        if (matrices == null) return;

        Vec3d cam = ctx.camera().getPos();
        Frustum frustum = ctx.frustum();
        double pauseDistSq = LumiereConfig.get().lazy.pause_distance_sq;

        Set<BlockPos> knownSnapshot;
        synchronized (textures) { knownSnapshot = new HashSet<>(knownPos); }
        Set<BlockPos> toRemove = new HashSet<>();

        for (BlockPos pos : knownSnapshot) {
            BlockState state = client.world.getBlockState(pos);
            if (state.getBlock() != ModBlocks.PROJECTOR) { toRemove.add(pos); continue; }

            BlockEntity be = client.world.getBlockEntity(pos);
            if (!(be instanceof ProjectorBlockEntity projector)) continue;
            if (projector.getVideoUrl().isEmpty()) continue;

            ScreenTexture tex;
            synchronized (textures) {
                tex = textures.get(pos);
                if (tex == null) { tex = new ScreenTexture(); textures.put(pos.toImmutable(), tex); }
            }
            tex.register();

            double dx = pos.getX() + 0.5 - cam.x;
            double dy = pos.getY() + 0.5 - cam.y;
            double dz = pos.getZ() + 0.5 - cam.z;
            double dist = dx*dx + dy*dy + dz*dz;

            if (dist > pauseDistSq) { VideoManager.lazyPause(pos); continue; }
            if (VideoManager.isLazyPaused(pos)) VideoManager.lazyResume(pos);
            if (frustum != null && !frustum.isVisible(new Box(pos).expand(0.5))) continue;

            Direction facing = state.get(ProjectorBlock.FACING);
            drawProjector(matrices, cam, pos, projector, tex, facing);
        }

        for (BlockPos pos : toRemove) untrackProjector(pos);
    }

    private static void drawProjector(MatrixStack matrices, Vec3d cam,
                                      BlockPos pos, ProjectorBlockEntity projector,
                                      ScreenTexture tex, Direction facing) {
        float w = projector.getScreenWidth();
        float h = projector.getScreenHeight();
        float bezel = LumiereConfig.get().screen.bezel_size;

        double cx = pos.getX() + 0.5 - cam.x;
        double cy = pos.getY() - cam.y;
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

        drawBezel(matrices, w, h, bezel);
        drawScreenQuad(matrices, w, h, tex);

        matrices.pop();
    }

    private static void drawBezel(MatrixStack matrices, float w, float h, float bezel) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        float z = 0.5005f;
        float x0 = -w/2f - bezel, x1 = -w/2f;
        float x2 =  w/2f,         x3 =  w/2f + bezel;
        float yTop = h + bezel,   yBot = -bezel;

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int C = 0;
        buf.vertex(mat, x0, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x0, yBot, z).color(C, C, C, 255);
        buf.vertex(mat, x1, yBot, z).color(C, C, C, 255);
        buf.vertex(mat, x1, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x2, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x2, yBot, z).color(C, C, C, 255);
        buf.vertex(mat, x3, yBot, z).color(C, C, C, 255);
        buf.vertex(mat, x3, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x0, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x3, yTop, z).color(C, C, C, 255);
        buf.vertex(mat, x3, h,    z).color(C, C, C, 255);
        buf.vertex(mat, x0, h,    z).color(C, C, C, 255);
        buf.vertex(mat, x0, 0f,   z).color(C, C, C, 255);
        buf.vertex(mat, x3, 0f,   z).color(C, C, C, 255);
        buf.vertex(mat, x3, yBot, z).color(C, C, C, 255);
        buf.vertex(mat, x0, yBot, z).color(C, C, C, 255);
        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void drawScreenQuad(MatrixStack matrices, float w, float h, ScreenTexture tex) {
        Matrix4f mat = matrices.peek().getPositionMatrix();
        float z = 0.501f;

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, tex.register());
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        float x0 = -w/2f, x1 = w/2f;
        int br = 255;
        // CCW: BL, BR, TR, TL — normal points +Z (outward, towards viewer)
        buf.vertex(mat, x0, 0f, z).texture(0, 1).color(br, br, br, 255);
        buf.vertex(mat, x1, 0f, z).texture(1, 1).color(br, br, br, 255);
        buf.vertex(mat, x1, h,  z).texture(1, 0).color(br, br, br, 255);
        buf.vertex(mat, x0, h,  z).texture(0, 0).color(br, br, br, 255);
        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    public static void clearAll() {
        Set<ScreenTexture> all;
        synchronized (textures) { all = new HashSet<>(textures.values()); textures.clear(); knownPos.clear(); }
        for (ScreenTexture tex : all) { try { tex.close(); } catch (Exception ignored) {} }
    }

    public static Map<BlockPos, ScreenTexture> getTextures() { return textures; }
}
