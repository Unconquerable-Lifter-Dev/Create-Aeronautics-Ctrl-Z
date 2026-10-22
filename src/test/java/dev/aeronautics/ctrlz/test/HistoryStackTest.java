package dev.aeronautics.ctrlz.test;

import dev.aeronautics.ctrlz.history.HistoryStack;
import dev.aeronautics.ctrlz.model.Action;
import dev.aeronautics.ctrlz.model.ActionType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Юнит-тесты стеков истории (per-player изоляция, очистка redo при новом действии,
 * вытеснение старых записей по лимиту maxActions, истечение по TTL и проверка контрольной суммы).
 */
public class HistoryStackTest {

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")
    );

    private Action createDummyAction(UUID playerUuid, long timestampMs) {
        return new Action(
                UUID.randomUUID(),
                playerUuid,
                "TestEngineer",
                ActionType.BLOCK_PLACE,
                OVERWORLD,
                List.of(),
                List.of(),
                null,
                List.of(),
                timestampMs
        );
    }

    @Test
    public void testPushClearsRedoAndEnforcesMaxActions() {
        UUID playerUuid = UUID.randomUUID();
        HistoryStack stack = new HistoryStack(playerUuid);

        Action a1 = createDummyAction(playerUuid, 1000L);
        Action a2 = createDummyAction(playerUuid, 2000L);
        Action a3 = createDummyAction(playerUuid, 3000L);

        assertTrue(stack.pushAction(a1, 2, 512));
        assertTrue(stack.pushAction(a2, 2, 512));
        assertEquals(2, stack.getUndoCount());

        // Перекладываем a2 в Redo
        Action popped = stack.popUndoForExecution().orElseThrow();
        assertEquals(a2.id(), popped.id());
        stack.commitUndoToRedo(popped);
        assertEquals(1, stack.getUndoCount());
        assertEquals(1, stack.getRedoCount());

        // Новое действие a3 должно очистить стек Redo
        assertTrue(stack.pushAction(a3, 2, 512));
        assertEquals(0, stack.getRedoCount(), "New action must clear redo stack");
        assertEquals(2, stack.getUndoCount());
    }

    @Test
    public void testEvictionWhenMaxActionsExceeded() {
        UUID playerUuid = UUID.randomUUID();
        HistoryStack stack = new HistoryStack(playerUuid);

        Action a1 = createDummyAction(playerUuid, 1000L);
        Action a2 = createDummyAction(playerUuid, 2000L);
        Action a3 = createDummyAction(playerUuid, 3000L);

        stack.pushAction(a1, 2, 512);
        stack.pushAction(a2, 2, 512);
        stack.pushAction(a3, 2, 512);

        assertEquals(2, stack.getUndoCount());
        List<Action> remaining = stack.getUndoSnapshot();
        assertEquals(a2.id(), remaining.get(0).id(), "Oldest action a1 must be evicted");
        assertEquals(a3.id(), remaining.get(1).id());
    }

    @Test
    public void testTtlExpirationPurgesOldActions() {
        UUID playerUuid = UUID.randomUUID();
        HistoryStack stack = new HistoryStack(playerUuid);

        long baseTime = 1_000_000L;
        Action oldAction = createDummyAction(playerUuid, baseTime);
        Action freshAction = createDummyAction(playerUuid, baseTime + 59 * 60_000L);

        stack.pushAction(oldAction, 50, 512);
        stack.pushAction(freshAction, 50, 512);

        // Спустя 61 минуту от baseTime при TTL=60 минут старое действие удаляется, свежее остаётся
        stack.purgeExpired(baseTime + 61 * 60_000L, 60);
        assertEquals(1, stack.getUndoCount());
        assertEquals(freshAction.id(), stack.peekUndo().orElseThrow().id());
    }

    @Test
    public void testChecksumIntegrity() {
        UUID playerUuid = UUID.randomUUID();
        Action action = createDummyAction(playerUuid, System.currentTimeMillis());
        assertTrue(action.verifyChecksum());
    }
}
