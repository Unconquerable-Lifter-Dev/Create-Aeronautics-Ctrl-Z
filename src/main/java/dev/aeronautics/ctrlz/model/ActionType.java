package dev.aeronautics.ctrlz.model;

public enum ActionType {
    BLOCK_PLACE("block_place"),
    BLOCK_BREAK("block_break"),
    KINETIC_PARAM_CHANGE("kinetic_param_change"),
    WRENCH_INTERACT("wrench_interact"),
    CONTRAPTION_ASSEMBLE("contraption_assemble"),
    CONTRAPTION_DISASSEMBLE("contraption_disassemble"),
    CASCADE_COMPOSITE("cascade_composite"),
    CUSTOM("custom");

    private final String id;

    ActionType(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static ActionType fromId(String id) {
        for (ActionType type : values()) {
            if (type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        return CUSTOM;
    }
}
