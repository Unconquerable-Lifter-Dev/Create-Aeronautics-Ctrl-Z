package dev.aeronautics.ctrlz.model;

public enum CascadeCause {
    DIRECT("direct"),
    TNT_EXPLOSION("tnt_explosion"),
    PISTON_PUSH_PULL("piston_push_pull"),
    FLUID_FLOW("fluid_flow"),
    CONTRAPTION_BREAK("contraption_break"),
    KINETIC_PROPAGATION("kinetic_propagation");

    private final String id;

    CascadeCause(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static CascadeCause fromId(String id) {
        for (CascadeCause cause : values()) {
            if (cause.id.equalsIgnoreCase(id)) {
                return cause;
            }
        }
        return DIRECT;
    }
}
