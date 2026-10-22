package dev.aeronautics.ctrlz;

import com.mojang.logging.LogUtils;
import dev.aeronautics.ctrlz.client.ClientInputHandler;
import dev.aeronautics.ctrlz.client.ModKeyBindings;
import dev.aeronautics.ctrlz.command.CtrlZCommands;
import dev.aeronautics.ctrlz.config.CtrlZConfig;
import dev.aeronautics.ctrlz.history.HistoryManager;
import dev.aeronautics.ctrlz.network.NetworkHandler;
import dev.aeronautics.ctrlz.recorder.ActionRecorder;
import dev.aeronautics.ctrlz.recorder.CascadeTracker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(AeronauticsCtrlZMod.MOD_ID)
public final class AeronauticsCtrlZMod {
    public static final String MOD_ID = "aeronautics_ctrlz";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsCtrlZMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, CtrlZConfig.SPEC, "aeronautics_ctrlz-server.toml");

        modEventBus.addListener(NetworkHandler::registerPayloads);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(ModKeyBindings::registerKeyMappings);
            NeoForge.EVENT_BUS.register(ClientInputHandler.class);
        }

        NeoForge.EVENT_BUS.register(ActionRecorder.class);
        NeoForge.EVENT_BUS.register(CascadeTracker.class);
        NeoForge.EVENT_BUS.register(HistoryManager.class);
        NeoForge.EVENT_BUS.register(CtrlZCommands.class);

        LOGGER.info("Create Aeronautics: Ctrl + Z initialized (Server-Authoritative Atomic Undo/Redo).");
    }
}
