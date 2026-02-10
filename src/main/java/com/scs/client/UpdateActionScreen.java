package com.scs.client;

import com.scs.client.update.UpdateCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UpdateActionScreen extends Screen {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;

    private final Screen returnScreen;
    private final String serverAddress;
    private final String updateBaseUrl;

    public UpdateActionScreen(Screen returnScreen, String serverAddress, String updateBaseUrl) {
        super(Component.translatable("screen.scs.update_options.title"));
        this.returnScreen = returnScreen;
        this.serverAddress = serverAddress == null ? "" : serverAddress;
        this.updateBaseUrl = updateBaseUrl;
    }

    @Override
    protected void init() {
        super.init();

        int totalWidth = (BUTTON_WIDTH * 2) + BUTTON_GAP;
        int leftX = (this.width - totalWidth) / 2;
        int centerY = this.height / 2;

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.scs.yes"),
                button -> UpdateCoordinator.startUpdate(updateBaseUrl, returnScreen, serverAddress)
        ).bounds(leftX, centerY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.scs.no"),
                button -> this.minecraft.setScreen(returnScreen)
        ).bounds(leftX + BUTTON_WIDTH + BUTTON_GAP, centerY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.scs.clear_cache"),
                button -> {
                    Minecraft minecraft = Minecraft.getInstance();
                    ConfirmScreen confirmScreen = new ConfirmScreen(
                            confirmed -> {
                                if (confirmed) {
                                    UpdateCoordinator.clearCache(returnScreen, serverAddress);
                                } else {
                                    minecraft.setScreen(this);
                                }
                            },
                            Component.translatable("screen.scs.clear_cache.title"),
                            Component.translatable("screen.scs.clear_cache.body"),
                            Component.translatable("gui.scs.clear"),
                            Component.translatable("gui.scs.back")
                    );
                    minecraft.setScreen(confirmScreen);
                }
        ).bounds(leftX, centerY + BUTTON_HEIGHT + 6, totalWidth, BUTTON_HEIGHT).build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(returnScreen);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.scs.update_this_server"), this.width / 2, (this.height / 2) - 40, 0xFFFFFF);
        String serverLabel = serverAddress.isBlank() ? "" : serverAddress;
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.scs.server_label", serverLabel), this.width / 2, (this.height / 2) - 26, 0xA0A0A0);
    }
}
