package com.playstudio.bridgemod.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.playstudio.bridgemod.bot.BotController;
import com.playstudio.bridgemod.handler.BotHandler;
import com.playstudio.bridgemod.pathfinding.PathNode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Client-side path visualization, ported from Baritone's PathRenderer.
 * Draws GL_LINES along calculated paths visible in-game (including through walls).
 */
public class PathRenderer {

    private final BotHandler botHandler;

    // Colors (RGBA 0-255)
    private static final int[] COLOR_CURRENT = {0, 255, 0, 180};     // green: active path
    private static final int[] COLOR_COMPLETED = {100, 100, 100, 100}; // gray: already walked
    private static final int[] COLOR_GOAL = {255, 0, 0, 200};         // red: goal marker

    private static final float LINE_WIDTH = 3.0f;

    public PathRenderer(BotHandler botHandler) {
        this.botHandler = botHandler;
    }

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var controllers = botHandler.getControllers();
        if (controllers == null || controllers.isEmpty()) return;

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();

        for (BotController controller : controllers.values()) {
            List<PathNode> path = controller.getCurrentPath();
            int pathIndex = controller.getCurrentPathIndex();
            if (path == null || path.size() < 2) continue;

            renderPath(poseStack, camera, path, pathIndex);
        }
    }

    private void renderPath(PoseStack poseStack, Vec3 camera, List<PathNode> path, int currentIndex) {
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        // Set up GL state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.lineWidth(LINE_WIDTH);
        RenderSystem.disableDepthTest();  // render through walls
        RenderSystem.disableCull();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        Matrix4f matrix = poseStack.last().pose();

        for (int i = 0; i < path.size() - 1; i++) {
            PathNode a = path.get(i);
            PathNode b = path.get(i + 1);

            int[] color;
            if (i < currentIndex) {
                color = COLOR_COMPLETED; // already passed
            } else {
                color = COLOR_CURRENT;   // upcoming
            }

            // Draw line segment at block center (+0.5), slightly above ground (+0.1)
            buffer.vertex(matrix, a.x + 0.5f, a.y + 0.1f, a.z + 0.5f)
                    .color(color[0], color[1], color[2], color[3]).endVertex();
            buffer.vertex(matrix, b.x + 0.5f, b.y + 0.1f, b.z + 0.5f)
                    .color(color[0], color[1], color[2], color[3]).endVertex();
        }

        // Draw goal marker (vertical line at last node)
        PathNode last = path.get(path.size() - 1);
        buffer.vertex(matrix, last.x + 0.5f, last.y, last.z + 0.5f)
                .color(COLOR_GOAL[0], COLOR_GOAL[1], COLOR_GOAL[2], COLOR_GOAL[3]).endVertex();
        buffer.vertex(matrix, last.x + 0.5f, last.y + 2.0f, last.z + 0.5f)
                .color(COLOR_GOAL[0], COLOR_GOAL[1], COLOR_GOAL[2], COLOR_GOAL[3]).endVertex();

        tesselator.end();

        // Restore GL state
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.lineWidth(1.0f);

        poseStack.popPose();
    }
}
