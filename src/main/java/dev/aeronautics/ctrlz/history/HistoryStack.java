package dev.aeronautics.ctrlz.history;

import dev.aeronautics.ctrlz.model.Action;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Потокобезопасный стек истории (undo / redo) для одного игрока.
 * Контролирует:
 * - maxActions (лимит глубины стека, вытеснение старых записей)
 * - ttlMillis (устаревание старых записей)
 * - maxSnapshotBytes (лимит памяти одного снимка/действия)
 * - очистку redo при записи нового действия
 */
public final class HistoryStack {
    private final UUID playerUuid;
    private final Deque<Action> undoDeque = new ArrayDeque<>();
    private final Deque<Action> redoDeque = new ArrayDeque<>();
    private long lastOperationTimestampMs = 0L;

    public HistoryStack(UUID playerUuid) {
        this.playerUuid = playerUuid;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public synchronized boolean pushAction(Action action, int maxActions, int maxSnapshotSizeKb) {
        int maxBytes = maxSnapshotSizeKb * 1024;
        if (action.estimateSizeBytes() > maxBytes) {
            return false;
        }
        redoDeque.clear();
        undoDeque.addLast(action);
        while (undoDeque.size() > maxActions) {
            undoDeque.removeFirst();
        }
        return true;
    }

    public synchronized void purgeExpired(long nowMs, long ttlMinutes) {
        if (ttlMinutes <= 0) return;
        long ttlMs = ttlMinutes * 60_000L;
        purgeDeque(undoDeque, nowMs, ttlMs);
        purgeDeque(redoDeque, nowMs, ttlMs);
    }

    private void purgeDeque(Deque<Action> deque, long nowMs, long ttlMs) {
        Iterator<Action> it = deque.iterator();
        while (it.hasNext()) {
            Action a = it.next();
            if (nowMs - a.timestampMs() > ttlMs) {
                it.remove();
            }
        }
    }

    public synchronized Optional<Action> peekUndo() {
        return Optional.ofNullable(undoDeque.peekLast());
    }

    public synchronized Optional<Action> peekRedo() {
        return Optional.ofNullable(redoDeque.peekLast());
    }

    public synchronized Optional<Action> popUndoForExecution() {
        return Optional.ofNullable(undoDeque.pollLast());
    }

    public synchronized void commitUndoToRedo(Action action) {
        redoDeque.addLast(action);
    }

    public synchronized void restoreFailedUndo(Action action) {
        undoDeque.addLast(action);
    }

    public synchronized Optional<Action> popRedoForExecution() {
        return Optional.ofNullable(redoDeque.pollLast());
    }

    public synchronized void commitRedoToUndo(Action action) {
        undoDeque.addLast(action);
    }

    public synchronized void restoreFailedRedo(Action action) {
        redoDeque.addLast(action);
    }

    public synchronized boolean checkAndUpdateCooldown(long nowMs, long cooldownMs) {
        if (nowMs - lastOperationTimestampMs < cooldownMs) {
            return false;
        }
        lastOperationTimestampMs = nowMs;
        return true;
    }

    public synchronized int getUndoCount() {
        return undoDeque.size();
    }

    public synchronized int getRedoCount() {
        return redoDeque.size();
    }

    public synchronized List<Action> getUndoSnapshot() {
        return new ArrayList<>(undoDeque);
    }

    public synchronized List<Action> getRedoSnapshot() {
        return new ArrayList<>(redoDeque);
    }

    public synchronized void clear() {
        undoDeque.clear();
        redoDeque.clear();
    }

    public synchronized CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("PlayerUuid", playerUuid);
        ListTag undoList = new ListTag();
        for (Action a : undoDeque) {
            undoList.add(a.toNbt());
        }
        tag.put("UndoStack", undoList);

        ListTag redoList = new ListTag();
        for (Action a : redoDeque) {
            redoList.add(a.toNbt());
        }
        tag.put("RedoStack", redoList);
        return tag;
    }

    public static HistoryStack fromNbt(CompoundTag tag, HolderGetter<Block> blockLookup) {
        UUID playerUuid = tag.getUUID("PlayerUuid");
        HistoryStack stack = new HistoryStack(playerUuid);
        ListTag undoList = tag.getList("UndoStack", Tag.TAG_COMPOUND);
        for (int i = 0; i < undoList.size(); i++) {
            stack.undoDeque.addLast(Action.fromNbt(undoList.getCompound(i), blockLookup));
        }
        ListTag redoList = tag.getList("RedoStack", Tag.TAG_COMPOUND);
        for (int i = 0; i < redoList.size(); i++) {
            stack.redoDeque.addLast(Action.fromNbt(redoList.getCompound(i), blockLookup));
        }
        return stack;
    }
}
