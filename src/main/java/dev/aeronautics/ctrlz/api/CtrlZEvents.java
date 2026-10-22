package dev.aeronautics.ctrlz.api;

import dev.aeronautics.ctrlz.engine.UndoRejectionReason;
import dev.aeronautics.ctrlz.model.Action;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * События жизненного цикла Undo/Redo для шины NeoForge.EVENT_BUS.
 */
public final class CtrlZEvents {
    private CtrlZEvents() {}

    public static final class BeforeUndoEvent extends Event implements ICancellableEvent {
        private final ServerPlayer executor;
        private final Action action;

        public BeforeUndoEvent(ServerPlayer executor, Action action) {
            this.executor = executor;
            this.action = action;
        }

        public ServerPlayer getExecutor() { return executor; }
        public Action getAction() { return action; }
    }

    public static final class AfterUndoEvent extends Event {
        private final ServerPlayer executor;
        private final Action action;

        public AfterUndoEvent(ServerPlayer executor, Action action) {
            this.executor = executor;
            this.action = action;
        }

        public ServerPlayer getExecutor() { return executor; }
        public Action getAction() { return action; }
    }

    public static final class BeforeRedoEvent extends Event implements ICancellableEvent {
        private final ServerPlayer executor;
        private final Action action;

        public BeforeRedoEvent(ServerPlayer executor, Action action) {
            this.executor = executor;
            this.action = action;
        }

        public ServerPlayer getExecutor() { return executor; }
        public Action getAction() { return action; }
    }

    public static final class AfterRedoEvent extends Event {
        private final ServerPlayer executor;
        private final Action action;

        public AfterRedoEvent(ServerPlayer executor, Action action) {
            this.executor = executor;
            this.action = action;
        }

        public ServerPlayer getExecutor() { return executor; }
        public Action getAction() { return action; }
    }

    public static final class UndoRejectedEvent extends Event {
        private final ServerPlayer executor;
        @Nullable
        private final Action action;
        private final boolean isRedo;
        private final UndoRejectionReason reason;
        private final String detail;

        public UndoRejectedEvent(
                ServerPlayer executor,
                @Nullable Action action,
                boolean isRedo,
                UndoRejectionReason reason,
                String detail
        ) {
            this.executor = executor;
            this.action = action;
            this.isRedo = isRedo;
            this.reason = reason;
            this.detail = detail;
        }

        public ServerPlayer getExecutor() { return executor; }
        @Nullable public Action getAction() { return action; }
        public boolean isRedo() { return isRedo; }
        public UndoRejectionReason getReason() { return reason; }
        public String getDetail() { return detail; }
    }
}
