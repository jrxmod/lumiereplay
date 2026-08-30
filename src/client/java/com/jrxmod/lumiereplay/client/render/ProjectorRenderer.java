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
import net.minecraft.world.LightType;
import net.minecraft.world.World;
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
        double pauseDistSq = LumiereConfig.get().lazy.pauseDistSq();

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
            drawProjector(matrices, cam, pos, projector, tex, facing,
                ambientDarkness(client.world, pos, facing));
        }

        for (BlockPos pos : toRemove) untrackProjector(pos);
    }

    private static void drawProjector(MatrixStack matrices, Vec3d cam,
                                      BlockPos pos, ProjectorBlockEntity projector,
                                      ScreenTexture tex, Direction facing, float darkness) {
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

        // A paused or broken screen must not keep a halo behind: only running media
        // lights the room, on the block and around the frame alike.
        if (projector.isPlaying()) drawGlow(matrices, w, h, bezel, tex, darkness);
        drawBezel(matrices, w, h, bezel);
        drawScreenQuad(matrices, w, h, tex);

        matrices.pop();
    }

    /**
     * Coloured spill of light around the frame, tinted by the average of the picture
     * and faded out towards the outside so that it has no hard edge. It is drawn
     * first and slightly behind the bezel, so the two later quads paint over its
     * middle and only the spill outside the frame stays visible.
     */
    private static void drawGlow(MatrixStack matrices, float w, float h,
                                 float bezel, ScreenTexture tex, float darkness) {
        if (darkness <= 0.02f) return;

        int luma = tex.getAvgLuma();
        if (luma < 16) return;

        int color = tex.getAvgColor();
        int r = (color >> 16) & 0xFF;
        int g = (color >>  8) & 0xFF;
        int b =  color         & 0xFF;

        int peak = (int) ((40 + luma * 70 / 255) * darkness);
        if (peak < 10) return;

        float spill = bezel + 0.40f + 0.85f * darkness;

        Matrix4f mat = matrices.peek().getPositionMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.enableCull();
        RenderSystem.depthMask(false);

        // One plane just in front of the block, behind the bezel and the screen, so
        // both later quads hide the middle of the glow and only the spill survives.
        float z   = 0.5001f;
        float xIn = w / 2f;                    // screen edge
        float xOu = w / 2f + bezel + spill;    // outer edge of the spill
        float yIn = -bezel;                    // bottom edge of the frame
        float yLo = -bezel - spill;            // below the frame
        float yHi = h + bezel + spill;         // above the frame

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        emitSpillY(buf, mat, z, -xOu, xOu, yLo, yIn, false, r, g, b, peak);
        emitSpillY(buf, mat, z, -xOu, xOu, yIn, yHi, true,  r, g, b, peak);
        emitSpillX(buf, mat, z, -xOu, -xIn, yLo, yHi, r, g, b, peak);
        emitSpillX(buf, mat, z,  xOu,  xIn, yLo, yHi, r, g, b, peak);
        BufferRenderer.drawWithGlobalProgram(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /**
     * A horizontal band of spill between two y levels, full alpha on the edge that
     * touches the frame and none on the outer one. Vertices are written counter
     * clockwise (bottom left, bottom right, top right, top left) so the front face
     * of the quad is the one that survives culling.
     */
    private static void emitSpillY(BufferBuilder buf, Matrix4f mat, float z,
                                   float x0, float x1, float yLow, float yHigh,
                                   boolean innerIsLow, int r, int g, int b, int a) {
        int aLow  = innerIsLow ? a : 0;
        int aHigh = innerIsLow ? 0 : a;
        buf.vertex(mat, x0, yLow, z).color(r, g, b, aLow);
        buf.vertex(mat, x1, yLow, z).color(r, g, b, aLow);
        buf.vertex(mat, x1, yHigh, z).color(r, g, b, aHigh);
        buf.vertex(mat, x0, yHigh, z).color(r, g, b, aHigh);
    }

    /**
     * A vertical band of spill between two x levels, the edge nearer to the screen
     * centre carrying the alpha. Winding stays counter clockwise for both sides.
     */
    private static void emitSpillX(BufferBuilder buf, Matrix4f mat, float z,
                                   float xOuter, float xInner, float yLow, float yHigh,
                                   int r, int g, int b, int a) {
        if (xOuter < xInner) {
            buf.vertex(mat, xOuter, yLow, z).color(r, g, b, 0);
            buf.vertex(mat, xInner, yLow, z).color(r, g, b, a);
            buf.vertex(mat, xInner, yHigh, z).color(r, g, b, a);
            buf.vertex(mat, xOuter, yHigh, z).color(r, g, b, 0);
        } else {
            buf.vertex(mat, xInner, yLow, z).color(r, g, b, a);
            buf.vertex(mat, xOuter, yLow, z).color(r, g, b, 0);
            buf.vertex(mat, xOuter, yHigh, z).color(r, g, b, 0);
            buf.vertex(mat, xInner, yHigh, z).color(r, g, b, a);
        }
    }

    /**
     * How dark the spot the screen shines into is, from nothing in open daylight to
     * one at night or under a roof. The light the projector itself adds to the world
     * is deliberately not counted here: it would silence the glow exactly where it is
     * supposed to be seen.
     */
    private static float ambientDarkness(World world, BlockPos pos, Direction facing) {
        if (world == null) return 1f;
        int sky = world.getLightLevel(LightType.SKY, pos.up().offset(facing));
        int effective = Math.max(0, sky - world.getAmbientDarkness());
        float dark = 1f - effective / 15f;
        return dark < 0f ? 0f : (dark > 1f ? 1f : dark);
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
