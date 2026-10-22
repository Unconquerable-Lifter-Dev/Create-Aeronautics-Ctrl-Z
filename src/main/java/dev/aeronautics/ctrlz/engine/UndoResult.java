package dev.aeronautics.ctrlz.engine;

public record UndoResult(
        boolean success,
        UndoRejectionReason reason,
        String translationKey,
        String detail
) {
    public static UndoResult ok(String translationKey, String detail) {
        return new UndoResult(true, UndoRejectionReason.NONE, translationKey, detail);
    }

    public static UndoResult reject(UndoRejectionReason reason, String detail) {
        return new UndoResult(false, reason, reason.getTranslationKey(), detail);
    }
}
