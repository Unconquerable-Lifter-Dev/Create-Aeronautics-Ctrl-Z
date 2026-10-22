package dev.aeronautics.ctrlz.contraption;

import dev.aeronautics.ctrlz.AeronauticsCtrlZMod;
import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.model.BlockDelta;
import dev.aeronautics.ctrlz.model.ContraptionSnapshot;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Модуль захвата и детерминированного восстановления контрапшенов Create и Create Aeronautics.
 *
 * Универсальный механизм (без жёсткой линковки к внешним JAR):
 * 1. Распознаёт сущности контрапшенов (`create:*contraption*`, `create_aeronautics:*contraption*`, PhysicsEntity/Airship)
 *    и контроллеры сборки (MechanicalBearing, ClockworkBearing, Gantry, Pulley, Helm/Levitator) через реестр и структуру NBT.
 * 2. Для детерминизма физики на время выполнения отката включает заморозку тика (`FROZEN_CONTRAPTIONS` через `EntityMixin`),
 *    обнуляет импульсы/угловые скорости, восстанавливает блоки, BlockEntity NBT и кинетическую сеть,
 *    и при необходимости триггерит повторную сборку (`assemble` / `Running = true`).
 */
public final class ContraptionStateHandler {
    private static final Set<UUID> FROZEN_CONTRAPTIONS = ConcurrentHashMap.newKeySet();

    private ContraptionStateHandler() {}

    public static boolean isContraptionFrozen(UUID entityUuid) {
        return FROZEN_CONTRAPTIONS.contains(entityUuid);
    }

    public static void freezeContraption(UUID entityUuid) {
        FROZEN_CONTRAPTIONS.add(entityUuid);
    }

    public static void unfreezeContraption(UUID entityUuid) {
        FROZEN_CONTRAPTIONS.remove(entityUuid);
    }

    public static boolean isContraptionEntity(Entity entity) {
        ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (typeKey == null) return false;
        String namespace = typeKey.getNamespace();
        String path = typeKey.getPath();
        return (namespace.equals("create") || namespace.equals("create_aeronautics"))
                && (path.contains("contraption") || path.contains("airship") || path.contains("physics"));
    }

    public static boolean isBearingOrContraptionController(BlockState state, @Nullable CompoundTag beNbt) {
        ResourceLocation blockKey = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockKey != null) {
            String path = blockKey.getPath();
            if (path.contains("bearing") || path.contains("piston") || path.contains("pulley")
                    || path.contains("gantry") || path.contains("chassis") || path.contains("helm")
                    || path.contains("propeller") || path.contains("gyro")) {
                return true;
            }
        }
        if (beNbt != null) {
            return beNbt.contains("Running") || beNbt.contains("Assembled") || beNbt.contains("Contraption");
        }
        return false;
    }

    /**
     * Захватывает состояние контрапшена перед разрушением опорного или сцепленного блока.
     * Покрывает основной сценарий P0: сломал блок -> контрапшен распался -> undo -> собрался обратно.
     */
    @Nullable
    public static ContraptionSnapshot captureBeforeBlockBreak(
            ServerLevel level,
            BlockPos brokenPos,
            BlockState brokenState,
            @Nullable CompoundTag brokenBeNbt
    ) {
        Entity nearbyContraption = findContraptionNear(level, brokenPos, 8.0);
        boolean controllerBlock = isBearingOrContraptionController(brokenState, brokenBeNbt);

        if (nearbyContraption == null && !controllerBlock) {
            // Проверим соседние блоки на наличие активного подшипника/контроллера
            BlockPos adjacentController = findAdjacentControllerPos(level, brokenPos);
            if (adjacentController == null) {
                return null;
            }
            return captureFromControllerPos(level, adjacentController, brokenPos, brokenState, brokenBeNbt);
        }

        if (nearbyContraption != null) {
            return captureFromContraptionEntity(level, nearbyContraption, brokenPos, brokenState, brokenBeNbt);
        }

        return captureFromControllerPos(level, brokenPos, brokenPos, brokenState, brokenBeNbt);
    }

    @Nullable
    public static ContraptionSnapshot captureControllerStateChange(
            ServerLevel level,
            BlockPos controllerPos,
            BlockState beforeState,
            BlockState afterState,
            @Nullable CompoundTag beforeBeNbt,
            @Nullable CompoundTag afterBeNbt
    ) {
        boolean runningBefore = beforeBeNbt != null && (beforeBeNbt.getBoolean("Running") || beforeBeNbt.getBoolean("Assembled"));
        boolean runningAfter = afterBeNbt != null && (afterBeNbt.getBoolean("Running") || afterBeNbt.getBoolean("Assembled"));

        Entity contraptionEntity = findContraptionNear(level, controllerPos, 6.0);
        CompoundTag entityTag = new CompoundTag();
        UUID contraptionId = UUID.randomUUID();
        Vec3 pos = Vec3.atCenterOf(controllerPos);
        Vec3 vel = Vec3.ZERO;
        Vec3 ang = Vec3.ZERO;
        List<CompoundTag> passengers = new ArrayList<>();

        if (contraptionEntity != null) {
            contraptionId = contraptionEntity.getUUID();
            contraptionEntity.save(entityTag);
            pos = contraptionEntity.position();
            vel = contraptionEntity.getDeltaMovement();
            ang = extractAngularVelocity(entityTag);
            if (CtrlZConfig.SERVER.restorePassengers.get()) {
                for (Entity passenger : contraptionEntity.getPassengers()) {
                    CompoundTag pTag = new CompoundTag();
                    passenger.saveWithoutId(pTag);
                    pTag.putUUID("PassengerUUID", passenger.getUUID());
                    passengers.add(pTag);
                }
            }
        }

        List<BlockDelta> blocks = captureConnectedStructureBlocks(level, controllerPos, 5);
        return new ContraptionSnapshot(
                contraptionId,
                controllerPos,
                runningBefore,
                runningAfter,
                runningBefore,
                runningAfter,
                pos,
                vel,
                ang,
                entityTag,
                beforeBeNbt != null ? beforeBeNbt : new CompoundTag(),
                afterBeNbt != null ? afterBeNbt : new CompoundTag(),
                blocks,
                passengers
        );
    }

    @Nullable
    private static ContraptionSnapshot captureFromContraptionEntity(
            ServerLevel level,
            Entity contraptionEntity,
            BlockPos brokenPos,
            BlockState brokenState,
            @Nullable CompoundTag brokenBeNbt
    ) {
        CompoundTag entityTag = new CompoundTag();
        contraptionEntity.save(entityTag);

        BlockPos anchor = extractAnchorPos(entityTag, brokenPos);
        BlockEntity controllerBe = level.getBlockEntity(anchor);
        CompoundTag ctrlBefore = ActionRecorder.captureBlockEntityNbt(level, controllerBe);

        List<BlockDelta> blocks = new ArrayList<>();
        blocks.add(new BlockDelta(brokenPos, brokenState, Blocks.AIR.defaultBlockState(), brokenBeNbt, null));
        blocks.addAll(extractBlocksFromContraptionNbt(level, anchor, entityTag));

        List<CompoundTag> passengers = new ArrayList<>();
        if (CtrlZConfig.SERVER.restorePassengers.get()) {
            for (Entity passenger : contraptionEntity.getPassengers()) {
                CompoundTag pTag = new CompoundTag();
                passenger.saveWithoutId(pTag);
                pTag.putUUID("PassengerUUID", passenger.getUUID());
                passengers.add(pTag);
            }
        }

        return new ContraptionSnapshot(
                contraptionEntity.getUUID(),
                anchor,
                true,
                false,
                true,
                false,
                contraptionEntity.position(),
                contraptionEntity.getDeltaMovement(),
                extractAngularVelocity(entityTag),
                entityTag,
                ctrlBefore != null ? ctrlBefore : new CompoundTag(),
                new CompoundTag(),
                blocks,
                passengers
        );
    }

    @Nullable
    private static ContraptionSnapshot captureFromControllerPos(
            ServerLevel level,
            BlockPos controllerPos,
            BlockPos brokenPos,
            BlockState brokenState,
            @Nullable CompoundTag brokenBeNbt
    ) {
        BlockEntity controllerBe = level.getBlockEntity(controllerPos);
        CompoundTag ctrlBefore = controllerPos.equals(brokenPos)
                ? brokenBeNbt
                : ActionRecorder.captureBlockEntityNbt(level, controllerBe);

        List<BlockDelta> blocks = captureConnectedStructureBlocks(level, controllerPos, 6);
        boolean wasRunning = ctrlBefore != null && (ctrlBefore.getBoolean("Running") || ctrlBefore.getBoolean("Assembled"));

        return new ContraptionSnapshot(
                UUID.randomUUID(),
                controllerPos,
                wasRunning,
                false,
                wasRunning,
                false,
                Vec3.atCenterOf(controllerPos),
                Vec3.ZERO,
                Vec3.ZERO,
                new CompoundTag(),
                ctrlBefore != null ? ctrlBefore : new CompoundTag(),
                new CompoundTag(),
                blocks,
                List.of()
        );
    }

    /**
     * Детерминированное восстановление контрапшена и кинетической сети при Undo / Redo.
     */
    public static boolean restoreContraptionState(
            ServerLevel level,
            ContraptionSnapshot snapshot,
            boolean isRedo
    ) {
        UUID contraptionId = snapshot.contraptionId();
        freezeContraption(contraptionId);
        try {
            boolean targetAssembled = isRedo ? snapshot.assembledAfter() : snapshot.assembledBefore();
            boolean targetPhysics = isRedo ? snapshot.physicsActiveAfter() : snapshot.physicsActiveBefore();
            CompoundTag targetControllerNbt = isRedo ? snapshot.controllerBeNbtAfter() : snapshot.controllerBeNbtBefore();

            // 1. Останавливаем и удаляем дубликат существующей сущности контрапшена, если она ещё в мире
            Entity existingEntity = level.getEntity(contraptionId);
            if (existingEntity != null) {
                existingEntity.setDeltaMovement(Vec3.ZERO);
                if (!targetAssembled) {
                    existingEntity.discard();
                }
            }

            // 2. Восстанавливаем все структурные блоки контрапшена и их BlockEntity NBT
            for (BlockDelta bd : snapshot.capturedBlocks()) {
                BlockState stateToApply = isRedo ? bd.afterState() : bd.beforeState();
                CompoundTag beNbtToApply = isRedo ? bd.afterBeNbt() : bd.beforeBeNbt();
                level.setBlock(bd.pos(), stateToApply, Block.UPDATE_ALL);
                if (beNbtToApply != null && !beNbtToApply.isEmpty()) {
                    BlockEntity be = level.getBlockEntity(bd.pos());
                    if (be != null) {
                        be.loadWithComponents(beNbtToApply, level.registryAccess());
                        be.setChanged();
                    }
                }
            }

            // 3. Восстанавливаем контроллер (подшипник/мотор/штурвал) на anchorPos
            BlockEntity anchorBe = level.getBlockEntity(snapshot.anchorPos());
            if (anchorBe != null && !targetControllerNbt.isEmpty()) {
                anchorBe.loadWithComponents(targetControllerNbt, level.registryAccess());
                anchorBe.setChanged();
                level.sendBlockUpdated(snapshot.anchorPos(), anchorBe.getBlockState(), anchorBe.getBlockState(), Block.UPDATE_ALL);
            }

            // 4. Если целевое состояние — собранный контрапшен (assembled = true),
            //    триггерим пересборку через контроллер или восстанавливаем сущность из снимка NBT
            if (targetAssembled) {
                boolean assembledViaController = tryInvokeControllerAssemble(anchorBe);
                if (!assembledViaController && !snapshot.contraptionEntityNbt().isEmpty()) {
                    CompoundTag entityNbt = snapshot.contraptionEntityNbt();
                    Entity recreated = EntityType.loadEntityRecursive(entityNbt, level, e -> {
                        e.moveTo(snapshot.position().x, snapshot.position().y, snapshot.position().z, e.getYRot(), e.getXRot());
                        e.setDeltaMovement(targetPhysics ? snapshot.linearVelocity() : Vec3.ZERO);
                        return e;
                    });
                    if (recreated != null && level.getEntity(recreated.getUUID()) == null) {
                        level.addFreshEntity(recreated);
                        restorePassengersIfConfigured(level, recreated, snapshot);
                    }
                }
            }
            return true;
        } catch (Exception ex) {
            AeronauticsCtrlZMod.LOGGER.error("Failed to restore contraption state for {}: {}", contraptionId, ex.getMessage(), ex);
            return false;
        } finally {
            unfreezeContraption(contraptionId);
        }
    }

    private static void restorePassengersIfConfigured(ServerLevel level, Entity contraptionEntity, ContraptionSnapshot snapshot) {
        if (!CtrlZConfig.SERVER.restorePassengers.get()) return;
        for (CompoundTag pTag : snapshot.passengerSnapshots()) {
            if (pTag.hasUUID("PassengerUUID")) {
                Entity passenger = level.getEntity(pTag.getUUID("PassengerUUID"));
                if (passenger != null) {
                    passenger.startRiding(contraptionEntity, true);
                }
            }
        }
    }

    /**
     * Универсальный рефлексивный вызов метода сборки на BlockEntity подшипника/контроллера Create (`assemble()`),
     * если мод Create загружен в рантайме, с безопасным фоллбэком на NBT.
     */
    private static boolean tryInvokeControllerAssemble(@Nullable BlockEntity controllerBe) {
        if (controllerBe == null) return false;
        try {
            Method assembleMethod = controllerBe.getClass().getMethod("assemble");
            assembleMethod.invoke(controllerBe);
            return true;
        } catch (NoSuchMethodException ignored) {
            // У данного контроллера метод называется иначе или сборка управляется полем Running в NBT
            return false;
        } catch (Exception e) {
            AeronauticsCtrlZMod.LOGGER.debug("Reflective assemble() call skipped: {}", e.getMessage());
            return false;
        }
    }

    @Nullable
    private static Entity findContraptionNear(ServerLevel level, BlockPos pos, double radius) {
        AABB box = new AABB(pos).inflate(radius);
        List<Entity> entities = level.getEntities((Entity) null, box, ContraptionStateHandler::isContraptionEntity);
        return entities.isEmpty() ? null : entities.getFirst();
    }

    @Nullable
    private static BlockPos findAdjacentControllerPos(ServerLevel level, BlockPos origin) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos adj = origin.relative(dir);
            BlockState st = level.getBlockState(adj);
            BlockEntity be = level.getBlockEntity(adj);
            CompoundTag nbt = ActionRecorder.captureBlockEntityNbt(level, be);
            if (isBearingOrContraptionController(st, nbt)) {
                return adj.immutable();
            }
        }
        return null;
    }

    private static List<BlockDelta> captureConnectedStructureBlocks(ServerLevel level, BlockPos anchor, int radius) {
        List<BlockDelta> list = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(
                anchor.offset(-radius, -radius, -radius),
                anchor.offset(radius, radius, radius))) {
            BlockState st = level.getBlockState(p);
            if (st.isAir() || st.is(Blocks.BEDROCK)) continue;
            BlockEntity be = level.getBlockEntity(p);
            if (CtrlZConfig.isBlockEntityBlacklisted(be)) continue;
            CompoundTag nbt = ActionRecorder.captureBlockEntityNbt(level, be);
            list.add(new BlockDelta(p.immutable(), st, st, nbt, nbt));
        }
        return list;
    }

    private static List<BlockDelta> extractBlocksFromContraptionNbt(ServerLevel level, BlockPos anchor, CompoundTag entityTag) {
        List<BlockDelta> list = new ArrayList<>();
        if (!entityTag.contains("Contraption", Tag.TAG_COMPOUND)) {
            return list;
        }
        CompoundTag contraptionTag = entityTag.getCompound("Contraption");
        if (!contraptionTag.contains("Blocks", Tag.TAG_COMPOUND)) {
            return list;
        }
        HolderGetter<Block> blockLookup = level.holderLookup(Registries.BLOCK);
        CompoundTag blocksCompound = contraptionTag.getCompound("Blocks");
        ListTag palette = blocksCompound.getList("Palette", Tag.TAG_COMPOUND);
        ListTag blockList = blocksCompound.getList("BlockList", Tag.TAG_COMPOUND);

        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            BlockPos localPos = NbtUtils.readBlockPos(entry, "Pos").orElse(BlockPos.ZERO);
            BlockPos worldPos = anchor.offset(localPos);
            int stateIdx = entry.getInt("State");
            BlockState state = (stateIdx >= 0 && stateIdx < palette.size())
                    ? NbtUtils.readBlockState(blockLookup, palette.getCompound(stateIdx))
                    : Blocks.AIR.defaultBlockState();
            CompoundTag beData = entry.contains("Data", Tag.TAG_COMPOUND) ? entry.getCompound("Data") : null;
            list.add(new BlockDelta(worldPos, state, Blocks.AIR.defaultBlockState(), beData, null));
        }
        return list;
    }

    private static BlockPos extractAnchorPos(CompoundTag entityTag, BlockPos fallback) {
        if (entityTag.contains("Contraption", Tag.TAG_COMPOUND)) {
            CompoundTag c = entityTag.getCompound("Contraption");
            if (c.contains("Anchor", Tag.TAG_INT_ARRAY) || c.contains("Anchor", Tag.TAG_COMPOUND)) {
                return NbtUtils.readBlockPos(c, "Anchor").orElse(fallback);
            }
        }
        return fallback;
    }

    private static Vec3 extractAngularVelocity(CompoundTag entityTag) {
        if (entityTag.contains("AngularVelocity", Tag.TAG_COMPOUND)) {
            CompoundTag ang = entityTag.getCompound("AngularVelocity");
            return new Vec3(ang.getDouble("X"), ang.getDouble("Y"), ang.getDouble("Z"));
        }
        return Vec3.ZERO;
    }
}
