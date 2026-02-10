package com.scs.client;

import com.scs.core.Config;
import com.scs.mixin.ScreenAccessorMixin;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ClientEventHandlers {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventHandlers.class);
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_X_OFFSET = 125;
    private static final int BUTTON_Y_START = 50;
    private static final int BUTTON_SPACING = 36;
    private static final int BOTTOM_MARGIN = 50;
    private static final Map<JoinMultiplayerScreen, List<Button>> UPDATE_BUTTONS = new WeakHashMap<>();

    private ClientEventHandlers() {
    }

    public static void registerClientEvents() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof JoinMultiplayerScreen joinScreen)) {
                return;
            }

            Config.reload();
            LOGGER.info("Multiplayer screen initialized. Adding server buttons.");
            ServerList serverList = new ServerList(Minecraft.getInstance());
            serverList.load();

            int buttonX = scaledWidth - BUTTON_X_OFFSET;
            int maxHeight = scaledHeight - BOTTOM_MARGIN;
            boolean metadataUpdated = false;
            List<Button> buttons = new ArrayList<>();

            for (int i = 0; i < serverList.size(); i++) {
                int y = BUTTON_Y_START + (i * BUTTON_SPACING);
                if (y + BUTTON_HEIGHT > maxHeight) {
                    break;
                }

                ServerData server = serverList.get(i);
                if (ServerMetadata.setDefaultIfMissing(server.ip)) {
                    metadataUpdated = true;
                }

                Button updateButton = createUpdateButton(buttonX, y, server, joinScreen);
                ((ScreenAccessorMixin) (Object) joinScreen).invokeAddRenderableWidget(updateButton);
                buttons.add(updateButton);
            }

            UPDATE_BUTTONS.put(joinScreen, buttons);

            // Fabric server list can consume mouse clicks before custom row-side buttons.
            // This prioritizes explicit Update buttons.
            ScreenMouseEvents.allowMouseClick(joinScreen).register((screenRef, mouseX, mouseY, mouseButton) -> {
                List<Button> updateButtons = UPDATE_BUTTONS.get(joinScreen);
                if (updateButtons == null || updateButtons.isEmpty()) {
                    return true;
                }
                for (Button updateButton : updateButtons) {
                    if (updateButton.mouseClicked(mouseX, mouseY, mouseButton)) {
                        LOGGER.info("Update button click consumed at x={}, y={}", mouseX, mouseY);
                        return false;
                    }
                }
                return true;
            });

            if (metadataUpdated) {
                ServerMetadata.saveMetadata();
            }
        });
    }

    private static Button createUpdateButton(int x, int y, ServerData server, JoinMultiplayerScreen returnScreen) {
        return Button.builder(
                Component.translatable("gui.scs.update"),
                button -> {
                    String updateBaseUrl = ServerMetadata.getMetadata(server.ip);
                    LOGGER.info("Update button clicked for server: {}", updateBaseUrl);
                    Minecraft.getInstance().setScreen(new UpdateActionScreen(returnScreen, server.ip, updateBaseUrl));
                }
        ).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
    }
}
