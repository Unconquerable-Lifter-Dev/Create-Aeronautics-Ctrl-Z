package dev.aeronautics.ctrlz.config;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Серверный TOML-конфиг со всеми требуемыми параметрами P0 и P1.
 */
public final class CtrlZConfig {
    public static final ModConfigSpec SPEC;
    public static final Server SERVER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new Server(builder);
        SPEC = builder.build();
    }

    public static final class Server {
        // [history]
        public final ModConfigSpec.IntValue maxActions;
        public final ModConfigSpec.IntValue ttlMinutes;
        public final ModConfigSpec.IntValue maxSnapshotSizeKb;
        public final ModConfigSpec.BooleanValue persistAcrossRestarts;

        // [undo]
        public final ModConfigSpec.BooleanValue enableUndo;
        public final ModConfigSpec.BooleanValue enableRedo;
        public final ModConfigSpec.IntValue cooldownMs;
        public final ModConfigSpec.BooleanValue singleplayerOnly;
        public final ModConfigSpec.BooleanValue debugLogging;

        // [cascade]
        public final ModConfigSpec.BooleanValue recordExplosions;
        public final ModConfigSpec.BooleanValue recordPistons;
        public final ModConfigSpec.BooleanValue recordFluids;
        public final ModConfigSpec.BooleanValue recordUnattributed;

        // [createAeronautics]
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.BooleanValue restorePassengers;
        public final ModConfigSpec.BooleanValue restoreInventories;
        public final ModConfigSpec.BooleanValue restoreScheduledTicks;

        // [permissions]
        public final ModConfigSpec.BooleanValue allowAdminUndoOthers;
        public final ModConfigSpec.IntValue adminPermissionLevel;

        // [blacklist]
        public final ModConfigSpec.ConfigValue<List<? extends String>> dimensions;
        public final ModConfigSpec.ConfigValue<List<? extends String>> blockEntities;

        Server(ModConfigSpec.Builder builder) {
            builder.push("history");
            maxActions = builder
                    .comment("Maximum number of actions stored per player in undo stack.")
                    .defineInRange("maxActions", 50, 1, 1000);
            ttlMinutes = builder
                    .comment("Time-to-live of recorded actions in minutes.")
                    .defineInRange("ttlMinutes", 60, 1, 10080);
            maxSnapshotSizeKb = builder
                    .comment("Maximum memory size of a single Action / Contraption snapshot in KB.")
                    .defineInRange("maxSnapshotSizeKb", 512, 16, 16384);
            persistAcrossRestarts = builder
                    .comment("Persist player undo/redo history across server restarts.")
                    .define("persistAcrossRestarts", false);
            builder.pop();

            builder.push("undo");
            enableUndo = builder
                    .comment("Master switch for Undo (Ctrl+Z).")
                    .define("enableUndo", true);
            enableRedo = builder
                    .comment("Master switch for Redo (Ctrl+Y / Ctrl+Shift+Z).")
                    .define("enableRedo", true);
            cooldownMs = builder
                    .comment("Minimum cooldown in milliseconds between undo/redo operations per player.")
                    .defineInRange("cooldownMs", 500, 0, 60000);
            singleplayerOnly = builder
                    .comment("If true, undo/redo is restricted to singleplayer integrated servers.")
                    .define("singleplayerOnly", false);
            debugLogging = builder
                    .comment("Enable detailed debug logging for undo/redo validation and rejections.")
                    .define("debugLogging", false);
            builder.pop();

            builder.push("cascade");
            recordExplosions = builder
                    .comment("Record block destruction cascades from player-ignited TNT and explosions.")
                    .define("recordExplosions", true);
            recordPistons = builder
                    .comment("Record block movements caused by player-activated pistons.")
                    .define("recordPistons", true);
            recordFluids = builder
                    .comment("Record fluid spreads, cobble/obsidian generation, and washed blocks from player-placed fluids.")
                    .define("recordFluids", true);
            recordUnattributed = builder
                    .comment("Record unattributed cascades (creepers, natural fire, random ticks). Disabled by default.")
                    .define("recordUnattributed", false);
            builder.pop();

            builder.push("createAeronautics");
            enabled = builder
                    .comment("Enable Create and Create Aeronautics contraption & kinetic network restoration.")
                    .define("enabled", true);
            restorePassengers = builder
                    .comment("Restore mounted passenger positions on contraption undo.")
                    .define("restorePassengers", false);
            restoreInventories = builder
                    .comment("Restore inventory contents inside BlockEntities and contraption storage.")
                    .define("restoreInventories", false);
            restoreScheduledTicks = builder
                    .comment("Restore scheduled block and fluid ticks after undo/redo.")
                    .define("restoreScheduledTicks", true);
            builder.pop();

            builder.push("permissions");
            allowAdminUndoOthers = builder
                    .comment("Allow server admins / operators to undo/redo actions of other players.")
                    .define("allowAdminUndoOthers", true);
            adminPermissionLevel = builder
                    .comment("Minimum vanilla permission level required for admin undo operations.")
                    .defineInRange("adminPermissionLevel", 2, 1, 4);
            builder.pop();

            builder.push("blacklist");
            dimensions = builder
                    .comment("List of dimension resource locations where undo/redo recording and execution are disabled.")
                    .defineListAllowEmpty("dimensions", List.of(), o -> o instanceof String);
            blockEntities = builder
                    .comment("List of BlockEntity registry IDs that cannot be recorded or rolled back.")
                    .defineListAllowEmpty("blockEntities", List.of("minecraft:command_block", "minecraft:structure_block"), o -> o instanceof String);
            builder.pop();
        }
    }

    public static boolean isDimensionBlacklisted(String dimensionId) {
        return SERVER.dimensions.get().contains(dimensionId);
    }

    public static boolean isBlockEntityBlacklisted(BlockEntity be) {
        if (be == null) return false;
        ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
        return key != null && SERVER.blockEntities.get().contains(key.toString());
    }
}
