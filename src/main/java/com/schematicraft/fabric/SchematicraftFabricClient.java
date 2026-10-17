package com.schematicraft.fabric;

import com.schematicraft.client.GlobalClientEvents;
import com.schematicraft.client.ModKeyBindings;
import com.schematicraft.lib.client.CameraMode;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric client entry point.
 *
 * Replaces the client half of the NeoForge @Mod constructor. It registers the
 * shared keybinds and wires the shared handlers and the CameraMode handlers to
 * the Fabric API callbacks, in place of the NeoForge event bus.
 *
 * Editor detection is runtime, via FabricLoader.isModLoaded, exactly as the
 * NeoForge build used ModList.isLoaded. The one editor on Fabric is Litematica
 * (a client-side mod); its glue is piece 4c. Building Gadgets 2 and Create have
 * no Fabric 1.21.1 build, so their integration code is excluded from this source
 * set (see build.gradle), and there is no server or networking half here: the
 * Fabric mod is client-only.
 */
public final class SchematicraftFabricClient implements ClientModInitializer {

    // Edge detector for the CameraMode left-click, polled each tick. CameraMode is
    // a global screenshot mode with no screen open, so its click cannot come
    // through a Screen; it is read from the raw GLFW mouse button state on the
    // press transition, the loader-neutral equivalent of the NeoForge
    // InputEvent.MouseButton handler.
    private boolean mouseWasDown = false;
    private boolean escWasDown = false;

    @Override
    public void onInitializeClient() {
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.OPEN_SCHEMATICRAFT);
        KeyBindingHelper.registerKeyBinding(ModKeyBindings.OPEN_API_KEY_SCREEN);

        boolean hasLitematica = FabricLoader.getInstance().isModLoaded("litematica");
        if (!hasLitematica) {
            SchematicraftFabric.LOGGER.warn("Litematica not found. Schematicraft will "
                    + "load and browse the cloud library, but has no editor to hand builds to.");
        }

        // Shared keybind poll, once per client tick (NeoForge InputEvent.Key).
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            GlobalClientEvents.onClientTick(mc);
            pollCameraMode(mc);
        });

        // Disconnect cleanup (NeoForge ClientPlayerNetworkEvent.LoggingOut).
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, mc) -> GlobalClientEvents.onDisconnect());

        // CameraMode capture overlay (NeoForge RenderGuiLayerEvent.Post). The HUD
        // callback hands a DrawContext; CameraMode draws only while it is active.
        HudRenderCallback.EVENT.register(
                (drawContext, tickCounter) -> CameraMode.renderOverlay(drawContext));
    }

    /**
     * Feed CameraMode the raw ESC and left-click presses while it is active. Only
     * polled during capture mode; press edges are detected so one physical press
     * maps to one call, matching the NeoForge action==1 (press) handlers.
     */
    private void pollCameraMode(MinecraftClient mc) {
        if (!CameraMode.isActive() || mc.getWindow() == null) {
            mouseWasDown = false;
            escWasDown = false;
            return;
        }
        long handle = mc.getWindow().getHandle();

        boolean escDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escDown && !escWasDown) {
            CameraMode.onKeyInput(GLFW.GLFW_KEY_ESCAPE, 1);
        }
        escWasDown = escDown;

        boolean mouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (mouseDown && !mouseWasDown) {
            CameraMode.onMouseClick(0, 1);
        }
        mouseWasDown = mouseDown;
    }
}
