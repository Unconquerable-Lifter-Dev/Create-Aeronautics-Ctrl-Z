package dev.aeronautics.ctrlz.history;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Персистентное хранилище истории на уровне сервера (Overworld DataStorage),
 * активируемое при history.persistAcrossRestarts = true.
 */
public final class HistorySavedData extends SavedData {
    private static final String DATA_NAME = "aeronautics_ctrlz_history";
    private final Map<UUID, HistoryStack> stacks = new ConcurrentHashMap<>();

    public HistorySavedData() {}

    public Map<UUID, HistoryStack> getStacks() {
        return stacks;
    }

    public static HistorySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        HistorySavedData data = new HistorySavedData();
        HolderLookup.RegistryLookup<Block> blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        ListTag list = tag.getList("PlayerStacks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag stackTag = list.getCompound(i);
            HistoryStack stack = HistoryStack.fromNbt(stackTag, blockLookup);
            data.stacks.put(stack.getPlayerUuid(), stack);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (HistoryStack stack : stacks.values()) {
            list.add(stack.toNbt());
        }
        tag.put("PlayerStacks", list);
        return tag;
    }

    public static HistorySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(HistorySavedData::new, HistorySavedData::load),
                DATA_NAME
        );
    }
}
