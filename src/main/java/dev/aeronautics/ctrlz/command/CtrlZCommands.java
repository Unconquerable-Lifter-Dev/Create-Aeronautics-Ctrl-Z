package dev.aeronautics.ctrlz.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.engine.UndoEngine;
import dev.aeronautics.ctrlz.engine.UndoResult;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.history.HistoryStack;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.permission.PermissionHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * Реализация серверных команд:
 * - /ctrlz history [player]
 * - /ctrlz clear [player]
 * - /ctrlz undo [player]
 * - /ctrlz redo [player]
 * - /ctrlz debug
 */
public final class CtrlZCommands {
    private CtrlZCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("ctrlz")
                .then(Commands.literal("history")
                        .executes(ctx -> showHistory(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(CtrlZConfig.SERVER.adminPermissionLevel.get()))
                                .executes(ctx -> showHistory(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("clear")
                        .executes(ctx -> clearHistory(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(CtrlZConfig.SERVER.adminPermissionLevel.get()))
                                .executes(ctx -> clearHistory(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("undo")
                        .executes(ctx -> runUndo(ctx, ctx.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(CtrlZConfig.SERVER.adminPermissionLevel.get()))
                                .executes(ctx -> runUndo(ctx, EntityArgument.getPlayer(ctx, "player"), true))))
                .then(Commands.literal("redo")
                        .executes(ctx -> runRedo(ctx, ctx.getSource().getPlayerOrException(), false))
                        .then(Commands.argument("player", EntityArgument.player())
                                .requires(src -> src.hasPermission(CtrlZConfig.SERVER.adminPermissionLevel.get()))
                                .executes(ctx -> runRedo(ctx, EntityArgument.getPlayer(ctx, "player"), true))))
                .then(Commands.literal("debug")
                        .requires(src -> src.hasPermission(CtrlZConfig.SERVER.adminPermissionLevel.get()))
                        .executes(CtrlZCommands::toggleDebug))
        );
    }

    private static int showHistory(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        HistoryStack stack = HistoryManager.getOrCreateStack(target.getUUID());
        List<Action> undoList = stack.getUndoSnapshot();
        List<Action> redoList = stack.getRedoSnapshot();

        ctx.getSource().sendSuccess(() -> Component.translatable(
                "ctrlz.cmd.history_header",
                target.getGameProfile().getName(),
                undoList.size(),
                redoList.size()
        ).withStyle(ChatFormatting.GOLD), false);

        int start = Math.max(0, undoList.size() - 5);
        for (int i = undoList.size() - 1; i >= start; i--) {
            Action a = undoList.get(i);
            String line = String.format(" - [%s] dim=%s deltas=%d cascades=%d size=%dB",
                    a.type().getId(),
                    a.dimension().location(),
                    a.primaryDeltas().size(),
                    a.cascades().size(),
                    a.estimateSizeBytes());
            ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int clearHistory(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer executor = ctx.getSource().getPlayer();
        if (executor != null && !PermissionHandler.canExecuteUndoRedo(executor, target.getUUID())) {
            ctx.getSource().sendFailure(Component.translatable("ctrlz.msg.no_permission", target.getGameProfile().getName()));
            return 0;
        }
        HistoryManager.getOrCreateStack(target.getUUID()).clear();
        HistoryManager.clearOpenActionContext(target.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "ctrlz.cmd.cleared", target.getGameProfile().getName()
        ).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int runUndo(CommandContext<CommandSourceStack> ctx, ServerPlayer target, boolean forceAdmin) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer executor = ctx.getSource().getPlayerOrException();
        UndoResult res = UndoEngine.executeUndo(executor, target.getUUID(), forceAdmin);
        sendResultFeedback(ctx.getSource(), res);
        return res.success() ? 1 : 0;
    }

    private static int runRedo(CommandContext<CommandSourceStack> ctx, ServerPlayer target, boolean forceAdmin) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer executor = ctx.getSource().getPlayerOrException();
        UndoResult res = UndoEngine.executeRedo(executor, target.getUUID(), forceAdmin);
        sendResultFeedback(ctx.getSource(), res);
        return res.success() ? 1 : 0;
    }

    private static int toggleDebug(CommandContext<CommandSourceStack> ctx) {
        boolean current = CtrlZConfig.SERVER.debugLogging.get();
        CtrlZConfig.SERVER.debugLogging.set(!current);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "ctrlz.cmd.debug_toggled", !current
        ).withStyle(ChatFormatting.AQUA), true);
        return 1;
    }

    private static void sendResultFeedback(CommandSourceStack source, UndoResult res) {
        if (res.success()) {
            source.sendSuccess(() -> Component.translatable(res.translationKey(), res.detail()).withStyle(ChatFormatting.GREEN), false);
        } else {
            source.sendFailure(Component.translatable(res.translationKey(), res.detail()));
        }
    }
}
