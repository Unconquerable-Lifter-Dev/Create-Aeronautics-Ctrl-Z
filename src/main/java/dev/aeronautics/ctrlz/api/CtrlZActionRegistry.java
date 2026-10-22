package dev.aeronautics.ctrlz.api;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Публичный реестр пользовательских типов действий внутри мода.
 */
public final class CtrlZActionRegistry {
    private static final Map<ResourceLocation, String> CUSTOM_ACTION_TYPES = new ConcurrentHashMap<>();

    private CtrlZActionRegistry() {}

    public static void registerCustomActionType(ResourceLocation id, String displayName) {
        CUSTOM_ACTION_TYPES.put(id, displayName);
    }

    public static Set<ResourceLocation> getRegisteredTypes() {
        return Set.copyOf(CUSTOM_ACTION_TYPES.keySet());
    }
}
