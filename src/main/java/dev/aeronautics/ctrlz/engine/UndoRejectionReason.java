package dev.aeronautics.ctrlz.engine;

public enum UndoRejectionReason {
    NONE("ctrlz.msg.success"),
    DISABLED("ctrlz.msg.disabled"),
    COOLDOWN("ctrlz.msg.cooldown"),
    HISTORY_EMPTY("ctrlz.msg.empty"),
    ACTION_EXPIRED("ctrlz.msg.expired"),
    NO_PERMISSION("ctrlz.msg.no_permission"),
    DIMENSION_BLACKLISTED("ctrlz.msg.dimension_blacklisted"),
    CHUNKS_UNLOADED("ctrlz.msg.chunks_unloaded"),
    CHECKSUM_MISMATCH("ctrlz.msg.checksum_mismatch"),
    CONFLICT_DETECTED("ctrlz.msg.conflict"),
    UNSAFE_CASCADE_REDO("ctrlz.msg.unsafe_cascade_redo"),
    ATOMIC_ROLLBACK_ON_ERROR("ctrlz.msg.atomic_rollback"),
    EVENT_CANCELED("ctrlz.msg.canceled");

    private final String translationKey;

    UndoRejectionReason(String translationKey) {
        this.translationKey = translationKey;
    }

    public String getTranslationKey() {
        return translationKey;
    }
}
