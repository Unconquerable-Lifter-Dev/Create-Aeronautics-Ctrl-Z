package dev.aeronautics.ctrlz.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/**
 * Перенастраиваемые клавиши:
 * - Ctrl + Z (Undo)
 * - Ctrl + Y (Redo)
 */
public final class ModKeyBindings {
    public static final String CATEGORY = "key.categories.aeronautics_ctrlz";

    public static final KeyMapping UNDO_KEY = new KeyMapping(
            "key.aeronautics_ctrlz.undo",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY
    );

    public static final KeyMapping REDO_KEY = new KeyMapping(
            "key.aeronautics_ctrlz.redo",
            KeyConflictContext.IN_GAME,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            CATEGORY
    );

    private ModKeyBindings() {}

    public static void registerKeyMappings(final RegisterKeyMappingsEvent event) {
        event.register(UNDO_KEY);
        event.register(REDO_KEY);
    }
}
