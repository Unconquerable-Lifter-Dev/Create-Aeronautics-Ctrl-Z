package dev.aeronautics.ctrlz.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Хранит атомарную дельту одного блока: состояние до и после, NBT BlockEntity и состояние жидкости.
 */
public final class BlockDelta {
    private final BlockPos pos;
    private final BlockState beforeState;
    private final BlockState afterState;
    @Nullable
    private final CompoundTag beforeBeNbt;
    @Nullable
    private final CompoundTag afterBeNbt;
    private final FluidState beforeFluid;
    private final FluidState afterFluid;

    public BlockDelta(
            BlockPos pos,
            BlockState beforeState,
            BlockState afterState,
            @Nullable CompoundTag beforeBeNbt,
            @Nullable CompoundTag afterBeNbt
    ) {
        this.pos = pos.immutable();
        this.beforeState = Objects.requireNonNull(beforeState, "beforeState");
        this.afterState = Objects.requireNonNull(afterState, "afterState");
        this.beforeBeNbt = beforeBeNbt != null ? beforeBeNbt.copy() : null;
        this.afterBeNbt = afterBeNbt != null ? afterBeNbt.copy() : null;
        this.beforeFluid = beforeState.getFluidState();
        this.afterFluid = afterState.getFluidState();
    }

    public BlockPos pos() {
        return pos;
    }

    public BlockState beforeState() {
        return beforeState;
    }

    public BlockState afterState() {
        return afterState;
    }

    @Nullable
    public CompoundTag beforeBeNbt() {
        return beforeBeNbt != null ? beforeBeNbt.copy() : null;
    }

    @Nullable
    public CompoundTag afterBeNbt() {
        return afterBeNbt != null ? afterBeNbt.copy() : null;
    }

    public FluidState beforeFluid() {
        return beforeFluid;
    }

    public FluidState afterFluid() {
        return afterFluid;
    }

    public BlockDelta withAfter(BlockState newAfterState, @Nullable CompoundTag newAfterBeNbt) {
        return new BlockDelta(this.pos, this.beforeState, newAfterState, this.beforeBeNbt, newAfterBeNbt);
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.put("BeforeState", NbtUtils.writeBlockState(beforeState));
        tag.put("AfterState", NbtUtils.writeBlockState(afterState));
        if (beforeBeNbt != null) {
            tag.put("BeforeBE", beforeBeNbt.copy());
        }
        if (afterBeNbt != null) {
            tag.put("AfterBE", afterBeNbt.copy());
        }
        return tag;
    }

    public static BlockDelta fromNbt(CompoundTag tag, HolderGetter<Block> blockLookup) {
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        BlockState beforeState = tag.contains("BeforeState")
                ? NbtUtils.readBlockState(blockLookup, tag.getCompound("BeforeState"))
                : Blocks.AIR.defaultBlockState();
        BlockState afterState = tag.contains("AfterState")
                ? NbtUtils.readBlockState(blockLookup, tag.getCompound("AfterState"))
                : Blocks.AIR.defaultBlockState();
        CompoundTag beforeBe = tag.contains("BeforeBE") ? tag.getCompound("BeforeBE") : null;
        CompoundTag afterBe = tag.contains("AfterBE") ? tag.getCompound("AfterBE") : null;
        return new BlockDelta(pos, beforeState, afterState, beforeBe, afterBe);
    }

    public static BlockDelta fromNbtDefaultLookup(CompoundTag tag) {
        return fromNbt(tag, BuiltInRegistries.BLOCK.asLookup());
    }

    public int estimateSizeBytes() {
        int size = 64;
        if (beforeBeNbt != null) {
            size += beforeBeNbt.sizeInBytes();
        }
        if (afterBeNbt != null) {
            size += afterBeNbt.sizeInBytes();
        }
        return size;
    }

    public void updateChecksum(CRC32 crc) {
        crc.update(pos.getX());
        crc.update(pos.getY());
        crc.update(pos.getZ());
        crc.update(beforeState.toString().getBytes(StandardCharsets.UTF_8));
        crc.update(afterState.toString().getBytes(StandardCharsets.UTF_8));
        if (beforeBeNbt != null) {
            crc.update(beforeBeNbt.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (afterBeNbt != null) {
            crc.update(afterBeNbt.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
