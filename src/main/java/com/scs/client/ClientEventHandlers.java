package com.scs.client;

import com.scs.core.SCS;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

@Mod.EventBusSubscriber(modid = SCS.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientEventHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventHandlers.class);
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_X_OFFSET = 125;
    private static final int BUTTON_Y_START = 50;
    private static final int BUTTON_SPACING = 36;
    private static final int BOTTOM_MARGIN = 50;
    private static final Map<JoinMultiplayerScreen, List<UpdateButtonTarget>> UPDATE_BUTTON_TARGETS = new WeakHashMap<>();

    private ClientEventHandlers() {
    }

    @SubscribeEvent
    public static void onMultiplayerScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        LOGGER.info("Multiplayer screen initialized. Adding server buttons.");
        ServerList serverList = new ServerList(Minecraft.getInstance());
        serverList.load();

        int buttonX = screen.width - BUTTON_X_OFFSET;
        int maxHeight = screen.height - BOTTOM_MARGIN;
        boolean metadataUpdated = false;
        List<UpdateButtonTarget> buttonTargets = new ArrayList<>();

        for (int i = 0; i < serverList.size(); i++) {
            int y = BUTTON_Y_START + (i * BUTTON_SPACING);
            if (y + BUTTON_HEIGHT > maxHeight) {
                break;
            }

            ServerData server = serverList.get(i);
            if (ServerMetadata.setDefaultIfMissing(server.ip)) {
                metadataUpdated = true;
            }

            event.addListener(createUpdateButton(buttonX, y, server, screen));
            buttonTargets.add(new UpdateButtonTarget(buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT, server.ip));
        }

        UPDATE_BUTTON_TARGETS.put(screen, buttonTargets);

        if (metadataUpdated) {
            ServerMetadata.saveMetadata();
        }
    }

    @SubscribeEvent
    public static void onMultiplayerMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0 || !(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        List<UpdateButtonTarget> buttonTargets = UPDATE_BUTTON_TARGETS.get(screen);
        if (buttonTargets == null || buttonTargets.isEmpty()) {
            return;
        }

        for (UpdateButtonTarget buttonTarget : buttonTargets) {
            if (!buttonTarget.contains(event.getMouseX(), event.getMouseY())) {
                continue;
            }

            openUpdateScreen(screen, buttonTarget.serverIp());
            if (event.isCancelable()) {
                event.setCanceled(true);
            }
            return;
        }
    }

    private static Button createUpdateButton(int x, int y, ServerData server, JoinMultiplayerScreen returnScreen) {
        return Button.builder(
                Component.translatable("gui.scs.update"),
                button -> openUpdateScreen(returnScreen, server.ip)
        ).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
    }

    private static void openUpdateScreen(JoinMultiplayerScreen returnScreen, String serverIp) {
        String updateBaseUrl = ServerMetadata.getMetadata(serverIp);
        LOGGER.info("Update button clicked for server: {}", updateBaseUrl);
        Minecraft.getInstance().setScreen(new UpdateActionScreen(returnScreen, serverIp, updateBaseUrl));
    }

    private record UpdateButtonTarget(int x, int y, int width, int height, String serverIp) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }
}