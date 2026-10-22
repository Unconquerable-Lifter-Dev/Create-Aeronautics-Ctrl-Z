package dev.aeronautics.ctrlz.permission;

import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.model.Action;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Серверная проверка прав доступа к откату/повтору.
 * Обычный игрок может откатывать только свои действия; администратор — чужие при включённом конфиге.
 */
public final class PermissionHandler {
    private PermissionHandler() {}

    public static boolean canExecuteUndoRedo(ServerPlayer executor, UUID targetPlayerUuid) {
        if (CtrlZConfig.SERVER.singleplayerOnly.get()) {
            if (executor.getServer() != null && executor.getServer().isDedicatedServer()) {
                return false;
            }
        }
        if (executor.getUUID().equals(targetPlayerUuid)) {
            return true;
        }
        if (!CtrlZConfig.SERVER.allowAdminUndoOthers.get()) {
            return false;
        }
        return executor.hasPermissions(CtrlZConfig.SERVER.adminPermissionLevel.get());
    }

    public static boolean canModifyAction(ServerPlayer executor, Action action) {
        return canExecuteUndoRedo(executor, action.playerUuid());
    }

    public static boolean isAdmin(ServerPlayer player) {
        return player.hasPermissions(CtrlZConfig.SERVER.adminPermissionLevel.get());
    }
}
