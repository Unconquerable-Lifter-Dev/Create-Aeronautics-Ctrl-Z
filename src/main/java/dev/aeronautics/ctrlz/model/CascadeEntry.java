package dev.aeronautics.ctrlz.model;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Запись одного элемента каскада (взрыв TNT, сдвиг поршнем, смыв водой/лавой),
 * жёстко привязанная к породившему её Action ID.
 */
public record CascadeEntry(
        BlockDelta delta,
        CascadeCause cause,
        UUID parentActionId,
        int sequenceOrder
) {
    public CascadeEntry {
        Objects.requireNonNull(delta, "delta");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(parentActionId, "parentActionId");
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.put("Delta", delta.toNbt());
        tag.putString("Cause", cause.getId());
        tag.putUUID("ParentActionId", parentActionId);
        tag.putInt("Order", sequenceOrder);
        return tag;
    }

    public static CascadeEntry fromNbt(CompoundTag tag, HolderGetter<Block> blockLookup) {
        BlockDelta delta = BlockDelta.fromNbt(tag.getCompound("Delta"), blockLookup);
        CascadeCause cause = CascadeCause.fromId(tag.getString("Cause"));
        UUID parentId = tag.getUUID("ParentActionId");
        int order = tag.getInt("Order");
        return new CascadeEntry(delta, cause, parentId, order);
    }

    public int estimateSizeBytes() {
        return delta.estimateSizeBytes() + 36;
    }

    public void updateChecksum(CRC32 crc) {
        delta.updateChecksum(crc);
        crc.update(cause.getId().getBytes(StandardCharsets.UTF_8));
        crc.update(parentActionId.toString().getBytes(StandardCharsets.UTF_8));
        crc.update(sequenceOrder);
    }
}
