package com.scs.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DownloadProgressScreen extends Screen {

    private static final int BUTTON_WIDTH = 100;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 8;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_MARGIN = 8;

    private volatile String downloadSource;
    private volatile String downloadLabel;
    private volatile int progress = 0;
    private volatile String downloadSpeed = "0 KB/s";
    private volatile String estimatedTimeRemaining = "";
    private Button cancelButton;
    private volatile boolean isProcessing = false;
    private volatile String processingTitle = "";
    private volatile String processingDetail = "";
    private volatile int processingProgress = 0;
    private volatile boolean processingHasProgress = false;
    private final Screen returnScreen;
    private volatile boolean showSummary = false;
    private volatile String summaryTitle = Component.translatable("screen.scs.update_complete").getString();
    private volatile List<String> summaryLines = Collections.emptyList();
    private volatile boolean isCancelled = false;
    private volatile List<String> detailLines = Collections.emptyList();
    private volatile boolean showDetails = false;
    private int summaryScroll = 0;
    private Button detailsButton;

    public DownloadProgressScreen(String downloadLabel, String downloadSource, Screen returnScreen) {
        super(Component.translatable("screen.scs.downloading.title"));
        this.downloadLabel = downloadLabel == null || downloadLabel.isBlank() ? Component.translatable("screen.scs.files").getString() : downloadLabel;
        this.downloadSource = downloadSource == null || downloadSource.isBlank() ? Component.translatable("screen.scs.source.server").getString() : downloadSource;
        this.returnScreen = returnScreen;
    }
    @Override
    protected void init() {
        super.init();

        int buttonY = (this.height / 2) + 50;

        cancelButton = Button.builder(Component.translatable("gui.scs.cancel"), (button) -> {
            if (!showSummary) {
                isCancelled = true; // Signal cancellation
            }
            minecraft.execute(() -> minecraft.setScreen(returnScreen)); // Return to the previous screen
        }).bounds(0, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build();

        detailsButton = Button.builder(Component.translatable("gui.scs.details"), (button) -> {
            if (!showSummary) {
                return;
            }
            showDetails = !showDetails;
            summaryScroll = 0;
            detailsButton.setMessage(Component.translatable(showDetails ? "gui.scs.summary" : "gui.scs.details"));
        }).bounds(0, buttonY, BUTTON_WIDTH, BUTTON_HEIGHT).build();

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(detailsButton);

        layoutButtons();
    }

    /**
     * Checks if the download has been cancelled.
     */
    public boolean isCancelled() {
        return isCancelled;
    }

    /**
     * Resets the UI for a new download.
     */
    public void startNewDownload(String downloadLabel, String downloadSource) {
        this.downloadLabel = downloadLabel == null || downloadLabel.isBlank() ? Component.translatable("screen.scs.files").getString() : downloadLabel;
        this.downloadSource = downloadSource == null || downloadSource.isBlank() ? Component.translatable("screen.scs.source.server").getString() : downloadSource;
        this.progress = 0;
        this.downloadSpeed = "0 KB/s";
        this.estimatedTimeRemaining = "";
        this.isProcessing = false;
        this.processingTitle = "";
        this.processingDetail = "";
        this.processingProgress = 0;
        this.processingHasProgress = false;
        this.showSummary = false;
        this.summaryTitle = Component.translatable("screen.scs.update_complete").getString();
        this.summaryLines = Collections.emptyList();
        this.detailLines = Collections.emptyList();
        this.showDetails = false;
        this.summaryScroll = 0;
        if (cancelButton != null) {
            cancelButton.setMessage(Component.translatable("gui.scs.cancel"));
        }
        if (detailsButton != null) {
            detailsButton.visible = false;
            detailsButton.active = false;
            detailsButton.setMessage(Component.translatable("gui.scs.details"));
        }

        layoutButtons();
    }

    /**
     * Updates the progress bar, download speed, and estimated time remaining.
     *
     * @param progress      The current progress (0-100).
     * @param downloadSpeed The current download speed in KB/s.
     * @param estimatedTimeRemaining Estimated time remaining (optional).
     */
    public void updateProgress(int progress, String downloadSpeed, String estimatedTimeRemaining) {
        this.progress = Math.min(100, Math.max(0, progress));
        this.downloadSpeed = downloadSpeed;
        this.estimatedTimeRemaining = estimatedTimeRemaining;
    }

    /**
     * Call this when extraction starts after download finishes.
     * Shows extraction info including last download speed.
     */
    public void startExtraction(String extractionMessage) {
        startProcessing(extractionMessage, Component.translatable("screen.scs.last_speed", downloadSpeed).getString());
    }

    public void startProcessing(String title, String detail) {
        updateProcessing(title, detail, 0, false);
    }

    public void updateProcessing(String title, String detail, int progress, boolean hasProgress) {
        this.isProcessing = true;
        this.processingTitle = title == null ? "" : title;
        this.processingDetail = detail == null ? "" : detail;
        this.processingProgress = Math.min(100, Math.max(0, progress));
        this.processingHasProgress = hasProgress;
    }

    public void showSummary(String title, List<String> lines) {
        showSummary(title, lines, Collections.emptyList());
    }

    public void showSummary(String title, List<String> summaryLines, List<String> detailLines) {
        this.showSummary = true;
        this.summaryTitle = title == null || title.isBlank() ? Component.translatable("screen.scs.update_complete").getString() : title;
        this.summaryLines = normalizeLines(summaryLines);
        this.detailLines = normalizeLines(detailLines);
        this.showDetails = false;
        this.summaryScroll = 0;
        this.isProcessing = false;
        this.processingTitle = "";
        this.processingDetail = "";
        this.progress = 100;
        if (cancelButton != null) {
            cancelButton.setMessage(Component.translatable("gui.scs.close"));
        }
        if (detailsButton != null) {
            boolean hasDetails = !this.detailLines.isEmpty();
            detailsButton.visible = hasDetails;
            detailsButton.active = hasDetails;
            detailsButton.setMessage(Component.translatable("gui.scs.details"));
        }

        layoutButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(guiGraphics);

        super.render(guiGraphics, mouseX, mouseY, partialTicks);

        if (showSummary) {
            renderSummary(guiGraphics);
            return;
        }

        if (isProcessing) {
            renderProcessing(guiGraphics);
            return;
        }

        renderDownloadProgress(guiGraphics);
    }

    private void renderDownloadProgress(GuiGraphics guiGraphics) {
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.scs.downloading.from", downloadLabel, downloadSource), this.width / 2, 20, 0xFFFFFF);

        int barWidth = 200;
        int barHeight = 20;
        int barX = (this.width - barWidth) / 2;
        int barY = this.height / 2;

        guiGraphics.drawCenteredString(this.font, downloadSpeed, this.width / 2, barY - 30, 0xFFFFFF);
        if (!estimatedTimeRemaining.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("screen.scs.eta", estimatedTimeRemaining), this.width / 2, barY - 55, 0xFFFFFF);
        }

        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFFAAAAAA);

        int progressWidth = (int) (barWidth * (progress / 100.0));
        guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xFF00FF00);

        guiGraphics.drawCenteredString(this.font, progress + "%", this.width / 2, barY + 5, 0xFFFFFF);
    }

    private void renderProcessing(GuiGraphics guiGraphics) {
        String title = processingTitle == null || processingTitle.isBlank()
                ? Component.translatable("screen.scs.processing").getString()
                : processingTitle;
        guiGraphics.drawCenteredString(this.font, title, this.width / 2, 20, 0xFFFFFF);

        int barWidth = 200;
        int barHeight = 20;
        int barX = (this.width - barWidth) / 2;
        int barY = this.height / 2;

        if (processingDetail != null && !processingDetail.isBlank()) {
            guiGraphics.drawCenteredString(this.font, processingDetail, this.width / 2, barY - 30, 0xFFFFFF);
        }

        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFFAAAAAA);

        int progressWidth = processingHasProgress
                ? (int) (barWidth * (processingProgress / 100.0))
                : 0;
        guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xFF00FF00);

        String progressLabel = processingHasProgress ? (processingProgress + "%") : "...";
        guiGraphics.drawCenteredString(this.font, progressLabel, this.width / 2, barY + 5, 0xFFFFFF);
    }

    private void renderSummary(GuiGraphics guiGraphics) {
        guiGraphics.drawCenteredString(this.font, summaryTitle, this.width / 2, 20, 0xFFFFFF);
        Component modeLabel = Component.translatable(showDetails ? "gui.scs.details" : "gui.scs.summary");
        guiGraphics.drawCenteredString(this.font, modeLabel, this.width / 2, 36, 0xA0A0A0);

        List<String> lines = showDetails ? detailLines : summaryLines;
        int maxWidth = Math.max(200, this.width - 40);
        int top = 50;
        int bottom = cancelButton != null ? cancelButton.getY() - 10 : this.height - 30;
        int lineHeight = 12;
        int maxVisibleLines = Math.max(1, (bottom - top) / lineHeight);

        List<net.minecraft.util.FormattedCharSequence> wrappedLines = wrapLines(lines, maxWidth);
        int totalLines = wrappedLines.size();
        int maxScroll = Math.max(0, totalLines - maxVisibleLines);
        summaryScroll = Math.min(summaryScroll, maxScroll);

        if (wrappedLines.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable("screen.scs.no_details"), this.width / 2, top, 0xFFFFFF);
            return;
        }

        int y = top;
        int end = Math.min(totalLines, summaryScroll + maxVisibleLines);
        for (int i = summaryScroll; i < end; i++) {
            guiGraphics.drawCenteredString(this.font, wrappedLines.get(i), this.width / 2, y, 0xFFFFFF);
            y += lineHeight;
        }

        if (maxScroll > 0) {
            renderScrollBar(guiGraphics, top, bottom, summaryScroll, maxScroll);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (showSummary) {
            int totalLines = wrapLines(showDetails ? detailLines : summaryLines, Math.max(200, this.width - 40)).size();
            int top = 50;
            int bottom = cancelButton != null ? cancelButton.getY() - 10 : this.height - 30;
            int maxVisibleLines = Math.max(1, (bottom - top) / 12);
            int maxScroll = Math.max(0, totalLines - maxVisibleLines);
            if (maxScroll > 0) {
                summaryScroll = Math.max(0, Math.min(maxScroll, summaryScroll - (int) Math.signum(scrollDelta)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

    private List<net.minecraft.util.FormattedCharSequence> wrapLines(List<String> lines, int maxWidth) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<net.minecraft.util.FormattedCharSequence> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(this.font.split(Component.literal(line), maxWidth));
        }
        return wrapped;
    }

    private List<String> normalizeLines(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    private void layoutButtons() {
        if (cancelButton == null) {
            return;
        }

        int buttonY = (this.height / 2) + 50;
        boolean showDetailsButton = detailsButton != null && detailsButton.visible;
        int totalWidth = showDetailsButton
                ? (BUTTON_WIDTH * 2) + BUTTON_GAP
                : BUTTON_WIDTH;
        int leftX = (this.width - totalWidth) / 2;

        if (showDetailsButton) {
            detailsButton.setX(leftX);
            detailsButton.setY(buttonY);
            cancelButton.setX(leftX + BUTTON_WIDTH + BUTTON_GAP);
            cancelButton.setY(buttonY);
        } else {
            cancelButton.setX(leftX);
            cancelButton.setY(buttonY);
        }
    }

    private void renderScrollBar(GuiGraphics guiGraphics, int top, int bottom, int scroll, int maxScroll) {
        int barHeight = Math.max(1, bottom - top);
        int x = this.width - SCROLLBAR_MARGIN - SCROLLBAR_WIDTH;

        guiGraphics.fill(x, top, x + SCROLLBAR_WIDTH, bottom, 0x60FFFFFF);

        int thumbHeight = Math.max(10, (int) Math.round((barHeight * (1.0 / (maxScroll + 1)))));
        int available = barHeight - thumbHeight;
        int thumbY = top + (int) Math.round((available * (scroll / (double) maxScroll)));
        guiGraphics.fill(x, thumbY, x + SCROLLBAR_WIDTH, thumbY + thumbHeight, 0xC0FFFFFF);
    }
}



