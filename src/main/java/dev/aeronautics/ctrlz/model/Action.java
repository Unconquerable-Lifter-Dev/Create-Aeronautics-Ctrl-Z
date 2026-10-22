package dev.aeronautics.ctrlz.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Основная иммутабельная (с контролируемым закрытием окна каскадов) модель действия:
 * Action { id, player, type, dimension, before/after (primaryChanges), cascades[], contraptionSnapshot, scheduledTicks[], timestamp, checksum }
 */
public final class Action {
    private final UUID id;
    private final UUID playerUuid;
    private final String playerName;
    private final ActionType type;
    private final ResourceKey<Level> dimension;
    private final List<BlockDelta> primaryDeltas;
    private final List<CascadeEntry> cascades;
    @Nullable
    private final ContraptionSnapshot contraptionSnapshot;
    private final List<ScheduledTickRecord> scheduledTicks;
    private final long timestampMs;
    private long checksum;

    public Action(
            UUID id,
            UUID playerUuid,
            String playerName,
            ActionType type,
            ResourceKey<Level> dimension,
            List<BlockDelta> primaryDeltas,
            List<CascadeEntry> cascades,
            @Nullable ContraptionSnapshot contraptionSnapshot,
            List<ScheduledTickRecord> scheduledTicks,
            long timestampMs
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.type = Objects.requireNonNull(type, "type");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.primaryDeltas = new ArrayList<>(Objects.requireNonNull(primaryDeltas, "primaryDeltas"));
        this.cascades = new ArrayList<>(Objects.requireNonNull(cascades, "cascades"));
        this.contraptionSnapshot = contraptionSnapshot;
        this.scheduledTicks = new ArrayList<>(Objects.requireNonNull(scheduledTicks, "scheduledTicks"));
        this.timestampMs = timestampMs;
        this.checksum = computeChecksum();
    }

    public UUID id() { return id; }
    public UUID playerUuid() { return playerUuid; }
    public String playerName() { return playerName; }
    public ActionType type() { return type; }
    public ResourceKey<Level> dimension() { return dimension; }
    public List<BlockDelta> primaryDeltas() { return Collections.unmodifiableList(primaryDeltas); }
    public List<CascadeEntry> cascades() { return Collections.unmodifiableList(cascades); }
    @Nullable
    public ContraptionSnapshot contraptionSnapshot() { return contraptionSnapshot; }
    public List<ScheduledTickRecord> scheduledTicks() { return Collections.unmodifiableList(scheduledTicks); }
    public long timestampMs() { return timestampMs; }
    public long checksum() { return checksum; }

    public synchronized void appendCascade(CascadeEntry entry) {
        if (!entry.parentActionId().equals(this.id)) {
            throw new IllegalArgumentException("CascadeEntry parentActionId does not match Action id");
        }
        this.cascades.add(entry);
        this.checksum = computeChecksum();
    }

    public synchronized void addScheduledTick(ScheduledTickRecord tickRecord) {
        this.scheduledTicks.add(tickRecord);
        this.checksum = computeChecksum();
    }

    public long computeChecksum() {
        CRC32 crc = new CRC32();
        crc.update(id.toString().getBytes(StandardCharsets.UTF_8));
        crc.update(playerUuid.toString().getBytes(StandardCharsets.UTF_8));
        crc.update(type.getId().getBytes(StandardCharsets.UTF_8));
        crc.update(dimension.location().toString().getBytes(StandardCharsets.UTF_8));
        for (BlockDelta bd : primaryDeltas) {
            bd.updateChecksum(crc);
        }
        for (CascadeEntry ce : cascades) {
            ce.updateChecksum(crc);
        }
        if (contraptionSnapshot != null) {
            contraptionSnapshot.updateChecksum(crc);
        }
        return crc.getValue();
    }

    public boolean verifyChecksum() {
        return this.checksum == computeChecksum();
    }

    public int estimateSizeBytes() {
        int total = 128;
        for (BlockDelta bd : primaryDeltas) {
            total += bd.estimateSizeBytes();
        }
        for (CascadeEntry ce : cascades) {
            total += ce.estimateSizeBytes();
        }
        if (contraptionSnapshot != null) {
            total += contraptionSnapshot.estimateSizeBytes();
        }
        for (ScheduledTickRecord st : scheduledTicks) {
            total += st.estimateSizeBytes();
        }
        return total;
    }

    public Set<ChunkPos> getAffectedChunks() {
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockDelta bd : primaryDeltas) {
            chunks.add(new ChunkPos(bd.pos()));
        }
        for (CascadeEntry ce : cascades) {
            chunks.add(new ChunkPos(ce.delta().pos()));
        }
        if (contraptionSnapshot != null) {
            chunks.add(new ChunkPos(contraptionSnapshot.anchorPos()));
            for (BlockDelta bd : contraptionSnapshot.capturedBlocks()) {
                chunks.add(new ChunkPos(bd.pos()));
            }
        }
        return chunks;
    }

    public Set<BlockPos> getAllAffectedPositions() {
        Set<BlockPos> positions = new HashSet<>();
        for (BlockDelta bd : primaryDeltas) {
            positions.add(bd.pos());
        }
        for (CascadeEntry ce : cascades) {
            positions.add(ce.delta().pos());
        }
        if (contraptionSnapshot != null) {
            positions.add(contraptionSnapshot.anchorPos());
            for (BlockDelta bd : contraptionSnapshot.capturedBlocks()) {
                positions.add(bd.pos());
            }
        }
        return positions;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("PlayerUuid", playerUuid);
        tag.putString("PlayerName", playerName);
        tag.putString("Type", type.getId());
        tag.putString("Dimension", dimension.location().toString());
        tag.putLong("Timestamp", timestampMs);
        tag.putLong("Checksum", checksum);

        ListTag primaryList = new ListTag();
        for (BlockDelta bd : primaryDeltas) {
            primaryList.add(bd.toNbt());
        }
        tag.put("PrimaryDeltas", primaryList);

        ListTag cascadeList = new ListTag();
        for (CascadeEntry ce : cascades) {
            cascadeList.add(ce.toNbt());
        }
        tag.put("Cascades", cascadeList);

        if (contraptionSnapshot != null) {
            tag.put("Contraption", contraptionSnapshot.toNbt());
        }

        ListTag ticksList = new ListTag();
        for (ScheduledTickRecord tr : scheduledTicks) {
            ticksList.add(tr.toNbt());
        }
        tag.put("ScheduledTicks", ticksList);
        return tag;
    }

    public static Action fromNbt(CompoundTag tag, HolderGetter<Block> blockLookup) {
        UUID id = tag.getUUID("Id");
        UUID playerUuid = tag.getUUID("PlayerUuid");
        String playerName = tag.getString("PlayerName");
        ActionType type = ActionType.fromId(tag.getString("Type"));
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("Dimension"))
        );
        long timestamp = tag.getLong("Timestamp");

        List<BlockDelta> primaryDeltas = new ArrayList<>();
        ListTag primaryList = tag.getList("PrimaryDeltas", Tag.TAG_COMPOUND);
        for (int i = 0; i < primaryList.size(); i++) {
            primaryDeltas.add(BlockDelta.fromNbt(primaryList.getCompound(i), blockLookup));
        }

        List<CascadeEntry> cascades = new ArrayList<>();
        ListTag cascadeList = tag.getList("Cascades", Tag.TAG_COMPOUND);
        for (int i = 0; i < cascadeList.size(); i++) {
            cascades.add(CascadeEntry.fromNbt(cascadeList.getCompound(i), blockLookup));
        }

        ContraptionSnapshot contraption = null;
        if (tag.contains("Contraption", Tag.TAG_COMPOUND)) {
            contraption = ContraptionSnapshot.fromNbt(tag.getCompound("Contraption"), blockLookup);
        }

        List<ScheduledTickRecord> ticks = new ArrayList<>();
        ListTag ticksList = tag.getList("ScheduledTicks", Tag.TAG_COMPOUND);
        for (int i = 0; i < ticksList.size(); i++) {
            ticks.add(ScheduledTickRecord.fromNbt(ticksList.getCompound(i)));
        }

        return new Action(id, playerUuid, playerName, type, dimension, primaryDeltas, cascades, contraption, ticks, timestamp);
    }
}
