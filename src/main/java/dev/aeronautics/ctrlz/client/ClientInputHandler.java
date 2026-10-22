package dev.aeronautics.ctrlz.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aeronautics.ctrlz.network.UndoRedoRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/**
 * Обработчик клиентского ввода для Ctrl+Z, Ctrl+Y и Ctrl+Shift+Z.
 * Гарантирует отсутствие срабатываний в чате, инвентаре, книгах, табличках и любых экранах с текстовым вводом.
 */
public final class ClientInputHandler {
    private ClientInputHandler() {}

    @SubscribeEvent
    public static void onKeyInput(final InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        if (isBlockedByScreenOrTextInput(mc)) {
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean ctrlDown = Screen.hasControlDown()
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean shiftDown = Screen.hasShiftDown()
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT);

        // 1. Ctrl + Shift + Z -> Redo
        if (ctrlDown && shiftDown && event.getKey() == GLFW.GLFW_KEY_Z) {
            PacketDistributor.sendToServer(new UndoRedoRequestPayload(true, false, Optional.empty()));
            return;
        }

        // 2. Ctrl + Y -> Redo (или перенастроенный бинд REDO_KEY)
        if (ModKeyBindings.REDO_KEY.consumeClick() || (ctrlDown && !shiftDown && event.getKey() == GLFW.GLFW_KEY_Y)) {
            PacketDistributor.sendToServer(new UndoRedoRequestPayload(true, false, Optional.empty()));
            return;
        }

        // 3. Ctrl + Z -> Undo (или перенастроенный бинд UNDO_KEY, если не зажат Shift)
        if (!shiftDown && (ModKeyBindings.UNDO_KEY.consumeClick() || (ctrlDown && event.getKey() == GLFW.GLFW_KEY_Z))) {
            PacketDistributor.sendToServer(new UndoRedoRequestPayload(false, false, Optional.empty()));
        }
    }

    public static boolean isBlockedByScreenOrTextInput(Minecraft mc) {
        Screen currentScreen = mc.screen;
        if (currentScreen == null) {
            return false;
        }
        // Любой открытый GUI (чат, инвентарь, книга, табличка, наковальня, терминалы Create и т.д.) блокирует хоткеи
        if (currentScreen instanceof ChatScreen
                || currentScreen instanceof AbstractContainerScreen<?>
                || currentScreen instanceof BookEditScreen
                || currentScreen instanceof SignEditScreen) {
            return true;
        }
        if (currentScreen.getFocused() instanceof EditBox) {
            return true;
        }
        return true;
    }
}
