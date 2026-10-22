package dev.aeronautics.ctrlz.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Снимок физического контрапшена Create / Create Aeronautics.
 * Для контрапшенов используется полный снимок (а не только одиночная дельта),
 * поскольку физическое тело и структура суб-чанка/сущности представляют единый граф,
 * требующий детерминированной остановки и пересборки при откате.
 */
public final class ContraptionSnapshot {
    private final UUID contraptionId;
    private final BlockPos anchorPos;
    private final boolean assembledBefore;
    private final boolean assembledAfter;
    private final boolean physicsActiveBefore;
    private final boolean physicsActiveAfter;
    private final Vec3 position;
    private final Vec3 linearVelocity;
    private final Vec3 angularVelocity;
    private final CompoundTag contraptionEntityNbt;
    private final CompoundTag controllerBeNbtBefore;
    private final CompoundTag controllerBeNbtAfter;
    private final List<BlockDelta> capturedBlocks;
    private final List<CompoundTag> passengerSnapshots;

    public ContraptionSnapshot(
            UUID contraptionId,
            BlockPos anchorPos,
            boolean assembledBefore,
            boolean assembledAfter,
            boolean physicsActiveBefore,
            boolean physicsActiveAfter,
            Vec3 position,
            Vec3 linearVelocity,
            Vec3 angularVelocity,
            CompoundTag contraptionEntityNbt,
            CompoundTag controllerBeNbtBefore,
            CompoundTag controllerBeNbtAfter,
            List<BlockDelta> capturedBlocks,
            List<CompoundTag> passengerSnapshots
    ) {
        this.contraptionId = contraptionId;
        this.anchorPos = anchorPos.immutable();
        this.assembledBefore = assembledBefore;
        this.assembledAfter = assembledAfter;
        this.physicsActiveBefore = physicsActiveBefore;
        this.physicsActiveAfter = physicsActiveAfter;
        this.position = position;
        this.linearVelocity = linearVelocity;
        this.angularVelocity = angularVelocity;
        this.contraptionEntityNbt = contraptionEntityNbt != null ? contraptionEntityNbt.copy() : new CompoundTag();
        this.controllerBeNbtBefore = controllerBeNbtBefore != null ? controllerBeNbtBefore.copy() : new CompoundTag();
        this.controllerBeNbtAfter = controllerBeNbtAfter != null ? controllerBeNbtAfter.copy() : new CompoundTag();
        this.capturedBlocks = List.copyOf(capturedBlocks);
        this.passengerSnapshots = List.copyOf(passengerSnapshots);
    }

    public UUID contraptionId() { return contraptionId; }
    public BlockPos anchorPos() { return anchorPos; }
    public boolean assembledBefore() { return assembledBefore; }
    public boolean assembledAfter() { return assembledAfter; }
    public boolean physicsActiveBefore() { return physicsActiveBefore; }
    public boolean physicsActiveAfter() { return physicsActiveAfter; }
    public Vec3 position() { return position; }
    public Vec3 linearVelocity() { return linearVelocity; }
    public Vec3 angularVelocity() { return angularVelocity; }
    public CompoundTag contraptionEntityNbt() { return contraptionEntityNbt.copy(); }
    public CompoundTag controllerBeNbtBefore() { return controllerBeNbtBefore.copy(); }
    public CompoundTag controllerBeNbtAfter() { return controllerBeNbtAfter.copy(); }
    public List<BlockDelta> capturedBlocks() { return Collections.unmodifiableList(capturedBlocks); }
    public List<CompoundTag> passengerSnapshots() { return Collections.unmodifiableList(passengerSnapshots); }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("ContraptionId", contraptionId);
        tag.putInt("AnchorX", anchorPos.getX());
        tag.putInt("AnchorY", anchorPos.getY());
        tag.putInt("AnchorZ", anchorPos.getZ());
        tag.putBoolean("AssembledBefore", assembledBefore);
        tag.putBoolean("AssembledAfter", assembledAfter);
        tag.putBoolean("PhysicsBefore", physicsActiveBefore);
        tag.putBoolean("PhysicsAfter", physicsActiveAfter);

        tag.putDouble("PosX", position.x);
        tag.putDouble("PosY", position.y);
        tag.putDouble("PosZ", position.z);
        tag.putDouble("VelX", linearVelocity.x);
        tag.putDouble("VelY", linearVelocity.y);
        tag.putDouble("VelZ", linearVelocity.z);
        tag.putDouble("AngX", angularVelocity.x);
        tag.putDouble("AngY", angularVelocity.y);
        tag.putDouble("AngZ", angularVelocity.z);

        tag.put("EntityNbt", contraptionEntityNbt.copy());
        tag.put("ControllerBefore", controllerBeNbtBefore.copy());
        tag.put("ControllerAfter", controllerBeNbtAfter.copy());

        ListTag blocksTag = new ListTag();
        for (BlockDelta bd : capturedBlocks) {
            blocksTag.add(bd.toNbt());
        }
        tag.put("CapturedBlocks", blocksTag);

        ListTag passengersTag = new ListTag();
        for (CompoundTag pTag : passengerSnapshots) {
            passengersTag.add(pTag.copy());
        }
        tag.put("Passengers", passengersTag);
        return tag;
    }

    public static ContraptionSnapshot fromNbt(CompoundTag tag, HolderGetter<Block> blockLookup) {
        UUID id = tag.getUUID("ContraptionId");
        BlockPos anchor = new BlockPos(tag.getInt("AnchorX"), tag.getInt("AnchorY"), tag.getInt("AnchorZ"));
        boolean assBefore = tag.getBoolean("AssembledBefore");
        boolean assAfter = tag.getBoolean("AssembledAfter");
        boolean physBefore = tag.getBoolean("PhysicsBefore");
        boolean physAfter = tag.getBoolean("PhysicsAfter");

        Vec3 pos = new Vec3(tag.getDouble("PosX"), tag.getDouble("PosY"), tag.getDouble("PosZ"));
        Vec3 vel = new Vec3(tag.getDouble("VelX"), tag.getDouble("VelY"), tag.getDouble("VelZ"));
        Vec3 ang = new Vec3(tag.getDouble("AngX"), tag.getDouble("AngY"), tag.getDouble("AngZ"));

        CompoundTag entityNbt = tag.getCompound("EntityNbt");
        CompoundTag ctrlBefore = tag.getCompound("ControllerBefore");
        CompoundTag ctrlAfter = tag.getCompound("ControllerAfter");

        List<BlockDelta> blocks = new ArrayList<>();
        ListTag blocksList = tag.getList("CapturedBlocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksList.size(); i++) {
            blocks.add(BlockDelta.fromNbt(blocksList.getCompound(i), blockLookup));
        }

        List<CompoundTag> passengers = new ArrayList<>();
        ListTag passList = tag.getList("Passengers", Tag.TAG_COMPOUND);
        for (int i = 0; i < passList.size(); i++) {
            passengers.add(passList.getCompound(i));
        }

        return new ContraptionSnapshot(
                id, anchor, assBefore, assAfter, physBefore, physAfter,
                pos, vel, ang, entityNbt, ctrlBefore, ctrlAfter, blocks, passengers
        );
    }

    public int estimateSizeBytes() {
        int size = 160 + contraptionEntityNbt.sizeInBytes()
                + controllerBeNbtBefore.sizeInBytes()
                + controllerBeNbtAfter.sizeInBytes();
        for (BlockDelta bd : capturedBlocks) {
            size += bd.estimateSizeBytes();
        }
        for (CompoundTag p : passengerSnapshots) {
            size += p.sizeInBytes();
        }
        return size;
    }

    public void updateChecksum(CRC32 crc) {
        crc.update(contraptionId.toString().getBytes(StandardCharsets.UTF_8));
        crc.update(anchorPos.getX());
        crc.update(anchorPos.getY());
        crc.update(anchorPos.getZ());
        crc.update(assembledBefore ? 1 : 0);
        crc.update(assembledAfter ? 1 : 0);
        for (BlockDelta bd : capturedBlocks) {
            bd.updateChecksum(crc);
        }
    }
}
