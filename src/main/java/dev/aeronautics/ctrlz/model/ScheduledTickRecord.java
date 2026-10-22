package dev.aeronautics.ctrlz.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ticks.TickPriority;

import java.util.Objects;

public record ScheduledTickRecord(
        BlockPos pos,
        ResourceLocation targetId,
        boolean isFluid,
        int delayTicks,
        TickPriority priority
) {
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putString("Target", targetId.toString());
        tag.putBoolean("Fluid", isFluid);
        tag.putInt("Delay", delayTicks);
        tag.putInt("Priority", priority.getValue());
        return tag;
    }

    public static ScheduledTickRecord fromNbt(CompoundTag tag) {
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        ResourceLocation target = ResourceLocation.parse(tag.getString("Target"));
        boolean isFluid = tag.getBoolean("Fluid");
        int delay = tag.getInt("Delay");
        TickPriority priority = TickPriority.byValue(tag.getInt("Priority"));
        return new ScheduledTickRecord(pos, target, isFluid, delay, priority);
    }

    public int estimateSizeBytes() {
        return 28 + targetId.toString().length() * 2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScheduledTickRecord that)) return false;
        return isFluid == that.isFluid &&
               delayTicks == that.delayTicks &&
               Objects.equals(pos, that.pos) &&
               Objects.equals(targetId, that.targetId) &&
               priority == that.priority;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pos, targetId, isFluid, delayTicks, priority);
    }
}
