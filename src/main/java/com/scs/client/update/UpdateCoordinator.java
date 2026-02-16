package com.scs.client.update;

import com.scs.client.DownloadProgressScreen;
import com.scs.client.ServerMetadata;
import com.scs.core.Checksum;
import com.scs.core.Config;
import com.scs.core.SCS;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public final class UpdateCoordinator {

    private static final int CONNECTION_TIMEOUT_MS = 5000;
    private static final long FILE_IO_TIMEOUT_MS = 15000L;
    private static final String MOD_ZIP_NAME = "mods.zip";
    private static final String CONFIG_ZIP_NAME = "config.zip";
    private static final String MODS_REMOVE_LIST_NAME = "modsToRemoveFromTheClient.json";
    private static final Path SERVER_CACHE_ROOT = Path.of("SCS/servers");
    private static final Path MOD_UNZIP_DESTINATION = Path.of("mods");
    private static final Path CONFIG_UNZIP_DESTINATION = Path.of("config");
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateCoordinator.class);
    private static final int MAX_CHANGE_LIST_ITEMS = 5;
    private static final Pattern TOML_ASSIGNMENT_PATTERN =
            Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern MODS_REMOVE_ITEM_PATTERN =
            Pattern.compile("\\\"([^\\\"]*)\\\"");

    private UpdateCoordinator() {
    }

    private static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private static final class UpdateSummary {
        private final String title;
        private final List<String> summaryLines;
        private final List<String> detailLines;

        private UpdateSummary(String title, List<String> summaryLines, List<String> detailLines) {
            this.title = title;
            this.summaryLines = summaryLines;
            this.detailLines = detailLines;
        }
    }

    private static final class UpdateOutcome {
        private final boolean cancelled;
        private final boolean success;
        private final Checksum.ChecksumDiff diff;

        private UpdateOutcome(boolean cancelled, boolean success, Checksum.ChecksumDiff diff) {
            this.cancelled = cancelled;
            this.success = success;
            this.diff = diff;
        }

        private static UpdateOutcome cancelled() {
            return new UpdateOutcome(true, false, null);
        }

        private static UpdateOutcome success(Checksum.ChecksumDiff diff) {
            return new UpdateOutcome(false, true, diff);
        }

        private static UpdateOutcome failed() {
            return new UpdateOutcome(false, false, null);
        }

        private boolean isCancelled() {
            return cancelled;
        }

        private boolean isSuccess() {
            return success;
        }

        private Checksum.ChecksumDiff getDiff() {
            return diff;
        }

        private boolean hasChanges() {
            return diff != null && !diff.isEmpty();
        }
    }
    public static void startUpdate(String updateBaseUrl) {
        startUpdate(updateBaseUrl, null, null);
    }

    public static void startUpdate(String updateBaseUrl, Screen returnScreenOverride) {
        startUpdate(updateBaseUrl, returnScreenOverride, null);
    }

    public static void startUpdate(String updateBaseUrl, Screen returnScreenOverride, String serverKey) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen returnScreen = returnScreenOverride != null ? returnScreenOverride : minecraft.screen;
        ServerCachePaths cachePaths = buildServerCachePaths(resolveServerKey(serverKey, updateBaseUrl));

        String modsUrl = buildDownloadUrl(updateBaseUrl, MOD_ZIP_NAME);
        if (modsUrl == null) {
            LOGGER.info("No mod URL found for {}", updateBaseUrl);
            showMissingUpdateUrlMessage(minecraft, returnScreen, updateBaseUrl, cachePaths.serverKey());
            return;
        }

        DownloadProgressScreen progressScreen = new DownloadProgressScreen(tr("screen.scs.label.mods"), modsUrl, returnScreen);
        minecraft.setScreen(progressScreen);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> performUpdateFlow(updateBaseUrl, modsUrl, minecraft, progressScreen, executor, cachePaths));
    }

    public static void clearCache(Screen returnScreen) {
        clearCache(returnScreen, null);
    }

    public static void clearCache(Screen returnScreen, String serverKey) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerCachePaths cachePaths = buildServerCachePaths(resolveServerKey(serverKey, null));
        DownloadProgressScreen progressScreen = new DownloadProgressScreen(tr("screen.scs.label.cache"), tr("screen.scs.source.local"), returnScreen);
        minecraft.setScreen(progressScreen);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                minecraft.execute(() -> progressScreen.startProcessing(tr("screen.scs.cache_clearing"), tr("screen.scs.cache_clearing_detail", cachePaths.serverKey())));
                ClearCacheResult result = clearCacheInternal(cachePaths);
                if (serverKey != null && !serverKey.isBlank()) {
                    ServerMetadata.removeMetadata(serverKey);
                }
                List<String> summary = List.of(
                        tr("screen.scs.cache_cleared_success"),
                        tr("screen.scs.cache_server_label", cachePaths.serverKey()),
                        tr("screen.scs.cache_deleted_files", result.deletedFiles),
                        tr("screen.scs.cache_deleted_dirs", result.deletedDirs)
                );
                minecraft.execute(() -> progressScreen.showSummary(tr("screen.scs.cache_cleared_title"), summary));
            } catch (Exception e) {
                LOGGER.error("Failed to clear cache", e);
                minecraft.execute(() -> progressScreen.showSummary(
                        tr("screen.scs.cache_clear_failed_title"),
                        List.of(tr("screen.scs.cache_clear_failed_body"))
                ));
            } finally {
                executor.shutdown();
            }
        });
    }

    private static void showMissingUpdateUrlMessage(Minecraft minecraft, Screen returnScreen, String updateBaseUrl, String serverKey) {
        String metadataValue = (updateBaseUrl == null || updateBaseUrl.isBlank()) ? "<empty>" : updateBaseUrl;
        DownloadProgressScreen progressScreen = new DownloadProgressScreen(tr("screen.scs.label.mods"), tr("screen.scs.source.server"), returnScreen);
        minecraft.setScreen(progressScreen);
        progressScreen.showSummary(
                tr("screen.scs.update_unavailable"),
                List.of(
                        tr("screen.scs.update_no_url"),
                        tr("screen.scs.update_open_settings"),
                        tr("screen.scs.update_server_cache_id", serverKey)
                ),
                List.of(tr("screen.scs.update_metadata_value", metadataValue))
        );
    }

    private static ClearCacheResult clearCacheInternal(ServerCachePaths cachePaths) throws IOException {
        int deletedFiles = 0;
        int deletedDirs = 0;

        Path[] cacheFiles = {cachePaths.modChecksumFile(), cachePaths.configChecksumFile()};
        for (Path cacheFile : cacheFiles) {
            if (Files.deleteIfExists(cacheFile)) {
                deletedFiles++;
            }
        }

        if (Files.exists(cachePaths.sharedFilesDir())) {
            try (var stream = Files.walk(cachePaths.sharedFilesDir()).sorted(Comparator.reverseOrder())) {
                for (Path path : stream.toList()) {
                    if (Files.isDirectory(path)) {
                        Files.deleteIfExists(path);
                        deletedDirs++;
                    } else {
                        Files.deleteIfExists(path);
                        deletedFiles++;
                    }
                }
            }
        }

        Files.createDirectories(cachePaths.sharedFilesDir());
        return new ClearCacheResult(deletedFiles, deletedDirs);
    }

    private static String resolveServerKey(String serverKey, String updateBaseUrl) {
        if (serverKey != null && !serverKey.isBlank()) {
            return serverKey;
        }
        if (updateBaseUrl != null && !updateBaseUrl.isBlank()) {
            return updateBaseUrl;
        }
        return "default";
    }

    private static ServerCachePaths buildServerCachePaths(String serverKey) {
        String safeServerKey = sanitizeServerKey(serverKey);
        Path serverRoot = SERVER_CACHE_ROOT.resolve(safeServerKey);
        Path sharedFilesDir = serverRoot.resolve("shared-files");
        return new ServerCachePaths(
                serverKey,
                serverRoot,
                sharedFilesDir,
                sharedFilesDir.resolve(MOD_ZIP_NAME),
                serverRoot.resolve("mods_checksums.json"),
                sharedFilesDir.resolve(CONFIG_ZIP_NAME),
                serverRoot.resolve("config_checksums.json")
        );
    }

    private static String sanitizeServerKey(String serverKey) {
        String raw = (serverKey == null || serverKey.isBlank()) ? "default" : serverKey.trim();
        String normalized = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        if (normalized.isBlank()) {
            normalized = "default";
        }
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48);
        }
        return normalized + "_" + Integer.toHexString(raw.hashCode());
    }

    private static final class ClearCacheResult {
        private final int deletedFiles;
        private final int deletedDirs;

        private ClearCacheResult(int deletedFiles, int deletedDirs) {
            this.deletedFiles = deletedFiles;
            this.deletedDirs = deletedDirs;
        }
    }
    private static final class ServerCachePaths {
        private final String serverKey;
        private final Path serverRoot;
        private final Path sharedFilesDir;
        private final Path modDownloadPath;
        private final Path modChecksumFile;
        private final Path configDownloadPath;
        private final Path configChecksumFile;

        private ServerCachePaths(
                String serverKey,
                Path serverRoot,
                Path sharedFilesDir,
                Path modDownloadPath,
                Path modChecksumFile,
                Path configDownloadPath,
                Path configChecksumFile
        ) {
            this.serverKey = serverKey;
            this.serverRoot = serverRoot;
            this.sharedFilesDir = sharedFilesDir;
            this.modDownloadPath = modDownloadPath;
            this.modChecksumFile = modChecksumFile;
            this.configDownloadPath = configDownloadPath;
            this.configChecksumFile = configChecksumFile;
        }

        private String serverKey() {
            return serverKey;
        }

        private Path serverRoot() {
            return serverRoot;
        }

        private Path sharedFilesDir() {
            return sharedFilesDir;
        }

        private Path modDownloadPath() {
            return modDownloadPath;
        }

        private Path modChecksumFile() {
            return modChecksumFile;
        }

        private Path configDownloadPath() {
            return configDownloadPath;
        }

        private Path configChecksumFile() {
            return configChecksumFile;
        }
    }

    private static void performUpdateFlow(
            String updateBaseUrl,
            String modsUrl,
            Minecraft minecraft,
            DownloadProgressScreen progressScreen,
            ExecutorService executor,
            ServerCachePaths cachePaths
    ) {
        UpdateOutcome modsOutcome = UpdateOutcome.failed();
        UpdateOutcome configOutcome = Config.updateConfig ? UpdateOutcome.failed() : UpdateOutcome.success(null);
        boolean cancelled = false;
        List<String> summaryExtras = new ArrayList<>();
        String currentModVersion = getCurrentModVersion();

        try {
            Files.createDirectories(cachePaths.serverRoot());
            Files.createDirectories(cachePaths.sharedFilesDir());
            LOGGER.info("Starting mod download from: {}", modsUrl);

            modsOutcome = downloadAndApplyZipUpdate(
                    minecraft,
                    progressScreen,
                    modsUrl,
                    "mods",
                    cachePaths.modDownloadPath(),
                    MOD_UNZIP_DESTINATION,
                    cachePaths.modChecksumFile(),
                    true,
                    Config.mirrorMods,
                    currentModVersion,
                    summaryExtras
            );

            if (modsOutcome.isCancelled()) {
                cancelled = true;
                return;
            }

            if (Config.updateConfig) {
                configOutcome = downloadConfigUpdate(updateBaseUrl, minecraft, progressScreen, cachePaths);
                if (configOutcome.isCancelled()) {
                    cancelled = true;
                    return;
                }
            }
        } catch (Throwable e) {
            LOGGER.error("Failed to download or extract mods", e);
            UpdateSummary summary = buildUpdateSummary(updateBaseUrl, modsOutcome, configOutcome, true, summaryExtras);
            minecraft.execute(() -> progressScreen.showSummary(summary.title, summary.summaryLines, summary.detailLines));
            sendPlayerMessages(minecraft, summary.summaryLines);
            return;
        } finally {
            executor.shutdown();
        }

        if (cancelled) {
            minecraft.execute(() -> progressScreen.showSummary(tr("screen.scs.update_cancelled"), List.of(tr("screen.scs.update_cancelled_by_user"))));
            sendPlayerMessages(minecraft, List.of(tr("screen.scs.update_cancelled_by_user")));
            return;
        }

        UpdateSummary summary = buildUpdateSummary(updateBaseUrl, modsOutcome, configOutcome, false, summaryExtras);
        minecraft.execute(() -> progressScreen.showSummary(summary.title, summary.summaryLines, summary.detailLines));
        sendPlayerMessages(minecraft, summary.summaryLines);
    }

    private static UpdateOutcome downloadConfigUpdate(
            String updateBaseUrl,
            Minecraft minecraft,
            DownloadProgressScreen progressScreen,
            ServerCachePaths cachePaths
    ) {
        String configUrl = buildDownloadUrl(updateBaseUrl, CONFIG_ZIP_NAME);
        if (configUrl == null) {
            LOGGER.warn("Config update enabled but no config URL found for {}", updateBaseUrl);
            return UpdateOutcome.failed();
        }

        LOGGER.info("Starting config download from: {}", configUrl);
        try {
            return downloadAndApplyZipUpdate(
                    minecraft,
                    progressScreen,
                    configUrl,
                    "config",
                    cachePaths.configDownloadPath(),
                    CONFIG_UNZIP_DESTINATION,
                    cachePaths.configChecksumFile(),
                    false,
                    Config.mirrorConfig,
                    null,
                    null
            );
        } catch (Throwable e) {
            LOGGER.error("Failed to download or extract config", e);
            return UpdateOutcome.failed();
        }
    }

    private static String buildDownloadUrl(String serverUpdateIP, String zipFileName) {
        if (serverUpdateIP == null || serverUpdateIP.isBlank()) {
            return null;
        }

        String baseUrl = serverUpdateIP;
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            baseUrl = "http://" + baseUrl;
        }

        String expectedSuffix = "/" + zipFileName;
        if (baseUrl.endsWith(expectedSuffix)) {
            return baseUrl;
        }

        int lastSlash = baseUrl.lastIndexOf('/');
        if (lastSlash > baseUrl.indexOf("://") + 2) {
            String lastSegment = baseUrl.substring(lastSlash + 1);
            if (lastSegment.endsWith(".zip")) {
                baseUrl = baseUrl.substring(0, lastSlash);
            }
        }

        if (baseUrl.endsWith("/")) {
            return baseUrl + zipFileName;
        }

        return baseUrl + expectedSuffix;
    }

    private static UpdateOutcome downloadAndApplyZipUpdate(
            Minecraft minecraft,
            DownloadProgressScreen progressScreen,
            String downloadUrl,
            String displayName,
            Path downloadPath,
            Path unzipDestination,
            Path checksumFile,
            boolean syncModsById,
            boolean mirrorMode,
            String currentModVersion,
            List<String> summaryExtras
    ) throws Exception {
        minecraft.execute(() -> progressScreen.startNewDownload(displayName, downloadUrl));

        HttpURLConnection connection = initializeConnection(downloadUrl, displayName);
        downloadFileWithProgress(connection, downloadPath, progressScreen);

        if (progressScreen.isCancelled()) {
            LOGGER.info("{} download cancelled by user.", displayName);
            return UpdateOutcome.cancelled();
        }

        minecraft.execute(() -> progressScreen.startProcessing("Preparing " + displayName + "...", "Validating download..."));
        validateDownloadedFile(downloadPath, displayName);
        prepareDestinationDirectory(unzipDestination);
        Set<String> extractedFiles = null;
        if (syncModsById) {
            LOGGER.info("Using modId sync extraction for {}", displayName);
            extractedFiles = extractModsZipFileWithModIdSync(
                    downloadPath,
                    unzipDestination,
                    progressScreen,
                    currentModVersion,
                    summaryExtras
            );
        } else {
            extractedFiles = extractZipFile(downloadPath, unzipDestination, progressScreen, displayName, "config/");
        }

        if (progressScreen.isCancelled()) {
            LOGGER.info("{} processing cancelled by user.", displayName);
            return UpdateOutcome.cancelled();
        }

        Set<String> mirrorAllowed = extractedFiles == null ? new HashSet<>() : new HashSet<>(extractedFiles);
        if (mirrorMode) {
            if (syncModsById) {
                mirrorAllowed.addAll(getSelfJarFileNames(unzipDestination, progressScreen));
            }
            MirrorResult mirrorResult = mirrorDirectoryContents(unzipDestination, mirrorAllowed, progressScreen, displayName);
            if (summaryExtras != null && mirrorResult.removedFiles > 0) {
                summaryExtras.add("Mirror removed " + mirrorResult.removedFiles + " extra file(s) from " + displayName + ".");
            }
        }

        if (progressScreen.isCancelled()) {
            LOGGER.info("{} processing cancelled by user.", displayName);
            return UpdateOutcome.cancelled();
        }

        Checksum.ChecksumDiff diff;
        if (mirrorMode) {
            diff = computeAndSaveChecksums(unzipDestination, checksumFile, progressScreen, displayName, null, false);
        } else if (!syncModsById) {
            diff = computeAndSaveChecksumsFromZip(
                    downloadPath,
                    checksumFile,
                    progressScreen,
                    displayName,
                    "config/"
            );
        } else {
            diff = computeAndSaveChecksums(
                    unzipDestination,
                    checksumFile,
                    progressScreen,
                    displayName,
                    extractedFiles,
                    syncModsById
            );
        }
        return UpdateOutcome.success(diff);
    }

    private static HttpURLConnection initializeConnection(String url, String displayName) throws IOException {
        URL downloadUrl;
        try {
            downloadUrl = URI.create(url).toURL();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid URL: " + url, e);
        }
        HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MS);
        connection.setRequestMethod("GET");

        int responseCode = connection.getResponseCode();
        LOGGER.info("Connecting to {} - Response Code: {}", url, responseCode);

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Failed to fetch " + displayName + " - Server returned response code: " + responseCode);
        }

        return connection;
    }

    private static void downloadFileWithProgress(HttpURLConnection connection, Path destination, DownloadProgressScreen progressScreen) throws IOException {
        Files.createDirectories(destination.getParent());
        if (!Files.exists(destination)) {
            Files.createFile(destination); // Create the file only if it doesn't exist
        }
        try (InputStream in = connection.getInputStream();
             var out = Files.newOutputStream(destination)) {
            long totalBytes = connection.getContentLengthLong();
            boolean hasLength = totalBytes > 0;
            long downloadedBytes = 0;
            long startTime = System.currentTimeMillis();

            byte[] buffer = new byte[8192];
            int bytesRead;
            String lastSpeed = "0 KB/s";

            while ((bytesRead = in.read(buffer)) != -1) {
                if (progressScreen.isCancelled()) {
                    LOGGER.info("Download cancelled by user.");
                    return;
                }

                out.write(buffer, 0, bytesRead);
                downloadedBytes += bytesRead;

                int progress = hasLength ? (int) ((downloadedBytes * 100) / totalBytes) : 0;
                long elapsedTime = System.currentTimeMillis() - startTime;
                double speedInKB = elapsedTime > 0 ? (downloadedBytes / 1024.0) / (elapsedTime / 1000.0) : 0.0;

                String speed = speedInKB >= 1024
                        ? String.format("%.2f MB/s", speedInKB / 1024)
                        : String.format("%.2f KB/s", speedInKB);
                lastSpeed = speed;

                // Calculate ETA
                long bytesRemaining = totalBytes - downloadedBytes;
                String eta;
                if (!hasLength) {
                    eta = "Unknown";
                } else {
                    double secondsRemaining = (speedInKB > 0) ? (bytesRemaining / 1024.0) / speedInKB : 0.0;
                    if (secondsRemaining > 0) {
                        int minutes = (int) (secondsRemaining / 60);
                        int seconds = (int) (secondsRemaining % 60);
                        eta = String.format("%dm %ds", minutes, seconds);
                    } else {
                        eta = "Calculating...";
                    }
                }

                progressScreen.updateProgress(progress, speed, eta);
            }

            if (!hasLength) {
                progressScreen.updateProgress(100, lastSpeed, "");
            }
        }
    }

    private static void validateDownloadedFile(Path downloadPath, String displayName) throws IOException {
        if (!Files.exists(downloadPath) || Files.size(downloadPath) == 0) {
            throw new IOException("Downloaded " + displayName + " file is invalid or empty.");
        }
    }

    private static void prepareDestinationDirectory(Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
        }
    }

    private static Checksum.ChecksumDiff computeAndSaveChecksums(
            Path targetDirectory,
            Path checksumFile,
            DownloadProgressScreen progressScreen,
            String displayName,
            Set<String> filterFiles,
            boolean jarOnly
    ) throws Exception {
        LOGGER.info("Comparing checksums...");
        updateProcessing(progressScreen, "Comparing " + displayName + " checksums...", "Scanning files...", 0, false);
        Checksum.ProgressListener listener = (current, total, fileName) -> {
            boolean hasTotal = total > 0;
            int progress = hasTotal ? (int) ((current * 100L) / total) : 0;
            String detail = hasTotal
                    ? String.format("%d/%d: %s", current, total, fileName)
                    : String.format("%d files... %s", current, fileName);
            updateProcessing(progressScreen, "Comparing " + displayName + " checksums...", detail, progress, total > 0);
        };

        Checksum.ChecksumResult result;
        if (filterFiles != null) {
            result = Checksum.compareChecksumsForPaths(targetDirectory, checksumFile, listener, filterFiles);
        } else if (jarOnly) {
            result = Checksum.compareChecksumsFlat(
                    targetDirectory,
                    checksumFile,
                    listener,
                    path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
            );
        } else {
            result = Checksum.compareChecksums(targetDirectory, checksumFile, listener);
        }
        Files.createDirectories(checksumFile.getParent());
        Checksum.saveChecksums(checksumFile, result.getNewChecksums());
        Checksum.ChecksumDiff diff = result.getDiff();
        if (filterFiles != null && !filterFiles.isEmpty()) {
            diff = Checksum.filterDiff(diff, filterFiles);
        }
        return diff;
    }

    private static final class MirrorResult {
        private final int removedFiles;

        private MirrorResult(int removedFiles, int removedDirs) {
            this.removedFiles = removedFiles;
        }
    }

    private static Set<String> getSelfJarFileNames(Path modsDirectory, DownloadProgressScreen progressScreen) {
        Map<String, List<Path>> byId = indexInstalledModsById(modsDirectory, progressScreen);
        List<Path> selfJars = byId.get(SCS.MODID.toLowerCase(Locale.ROOT));
        if (selfJars == null || selfJars.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (Path jar : selfJars) {
            names.add(jar.getFileName().toString());
        }
        return names;
    }

    private static MirrorResult mirrorDirectoryContents(
            Path destination,
            Set<String> allowedRelativePaths,
            DownloadProgressScreen progressScreen,
            String displayName
    ) throws IOException {
        if (!Files.exists(destination)) {
            return new MirrorResult(0, 0);
        }

        Set<String> allowed = normalizeAllowedPaths(allowedRelativePaths);
        List<Path> files;
        try (var stream = Files.walk(destination)) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }

        int total = files.size();
        int removedFiles = 0;
        for (int i = 0; i < total; i++) {
            Path file = files.get(i);
            int current = i + 1;
            if (current == 1 || current == total || current % 20 == 0) {
                String rel = normalizeRelativeKey(destination, file);
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                updateProcessing(progressScreen, "Mirroring " + displayName + "...", String.format("%d/%d: %s", current, total, rel), progress, total > 0);
            }

            if (progressScreen.isCancelled()) {
                break;
            }

            String rel = normalizeRelativeKey(destination, file);
            if (!allowed.contains(rel)) {
                try {
                    Files.deleteIfExists(file);
                    removedFiles++;
                } catch (Exception e) {
                    LOGGER.warn("Failed to remove extra {} file: {}", displayName, file, e);
                }
            }
        }

        int removedDirs = 0;
        try (var stream = Files.walk(destination).sorted(Comparator.reverseOrder())) {
            for (Path path : stream.toList()) {
                if (path.equals(destination) || !Files.isDirectory(path)) {
                    continue;
                }
                if (isDirectoryEmpty(path)) {
                    Files.deleteIfExists(path);
                    removedDirs++;
                }
            }
        }

        return new MirrorResult(removedFiles, removedDirs);
    }

    private static boolean isDirectoryEmpty(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }

    private static Set<String> normalizeAllowedPaths(Set<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> normalized = new HashSet<>();
        for (String entry : allowed) {
            String key = normalizeRelativeKey(entry);
            if (!key.isBlank()) {
                normalized.add(key);
            }
        }
        return normalized;
    }

    private static String normalizeRelativeKey(Path root, Path file) {
        String rel = root.relativize(file).toString();
        return normalizeRelativeKey(rel);
    }

    private static String normalizeRelativeKey(String rel) {
        if (rel == null) {
            return "";
        }
        return rel.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
    private static Set<String> extractZipFile(
            Path zipPath,
            Path destination,
            DownloadProgressScreen progressScreen,
            String displayName,
            String rootPrefixToStrip
    ) throws IOException {
        Set<String> extractedFiles = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
            int total = entries.size();
            int current = 0;
            for (ZipEntry entry : entries) {
                current++;
                String entryName = normalizeZipEntryName(entry.getName(), rootPrefixToStrip);
                if (entryName.isBlank()) {
                    continue;
                }
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                String detail = total > 0
                        ? String.format("%d/%d: %s", current, total, entryName)
                        : entryName;
                updateProcessing(progressScreen, "Extracting " + displayName + "...", detail, progress, total > 0);

                Path entryPath = destination.resolve(entryName).normalize();
                if (!entryPath.startsWith(destination)) {
                    throw new IOException("Blocked zip entry outside destination: " + entryName);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    extractedFiles.add(entryName.replace('\\', '/'));
                    LOGGER.info("Extracted {} entry: {}", displayName, entryName);
                }
            }
        }
        return extractedFiles;
    }

    private static Set<String> extractModsZipFileWithModIdSync(
            Path zipPath,
            Path destination,
            DownloadProgressScreen progressScreen,
            String currentModVersion,
            List<String> summaryExtras
    ) throws Exception {
        Map<String, List<Path>> existingModsById = indexInstalledModsById(destination, progressScreen);
        Set<String> extractedFiles = new HashSet<>();
        List<String> modsToRemove = new ArrayList<>();
        boolean warnedSelfUpdate = false;

        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
            int total = entries.size();
            int current = 0;

            for (ZipEntry entry : entries) {
                current++;

                if (progressScreen.isCancelled()) {
                    LOGGER.info("Mod extraction cancelled by user at entry {}/{}", current, total);
                    break;
                }

                String entryName = entry.getName();
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                String detail = total > 0
                        ? String.format("%d/%d: %s", current, total, entryName)
                        : entryName;

                LOGGER.info("Processing mods zip entry {}/{}: {}", current, total, entryName);

                if (MODS_REMOVE_LIST_NAME.equals(entryName)) {
                    updateProcessing(progressScreen, "Extracting mods...", detail, progress, total > 0);
                    modsToRemove = parseModsRemovalList(zipFile, entry);
                    LOGGER.info("Parsed {} entries from {}", modsToRemove.size(), MODS_REMOVE_LIST_NAME);
                    continue;
                }

                Path entryPath = destination.resolve(entryName).normalize();
                if (!entryPath.startsWith(destination)) {
                    throw new IOException("Blocked zip entry outside destination: " + entryName);
                }

                updateProcessing(progressScreen, "Extracting mods...", detail, progress, total > 0);

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                    continue;
                }

                Files.createDirectories(entryPath.getParent());
                boolean isJar = entryName.toLowerCase(Locale.ROOT).endsWith(".jar");

                if (isJar) {
                    byte[] jarBytes;
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        jarBytes = is.readAllBytes();
                    }

                    Set<String> modIds = Collections.emptySet();
                    Map<String, String> modVersions = Collections.emptyMap();
                    try {
                        String tomlText = readModsTomlTextFromJarBytes(jarBytes);
                        modIds = extractModIdsFromTomlText(tomlText);
                        modVersions = extractModVersionsFromTomlText(tomlText);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to identify modId for {} - extracting without duplicate cleanup.", entryName, e);
                    }

                    if (!modIds.isEmpty()) {
                        LOGGER.info("Zip entry {} has modId(s): {}", entryName, String.join(", ", modIds));
                    }

                    boolean isSelfJar = modIds.contains(SCS.MODID.toLowerCase(Locale.ROOT));
                    if (isSelfJar) {
                        if (!warnedSelfUpdate && summaryExtras != null) {
                            String zipVersion = modVersions.get(SCS.MODID.toLowerCase(Locale.ROOT));
                            if (zipVersion != null
                                    && currentModVersion != null
                                    && !currentModVersion.isBlank()
                                    && !"unknown".equalsIgnoreCase(currentModVersion)
                                    && !zipVersion.contains("${")) {
                                int comparison = compareVersions(zipVersion, currentModVersion);
                                if (comparison != 0) {
                                    summaryExtras.add(tr("screen.scs.warn_self_update", zipVersion, currentModVersion));
                                    warnedSelfUpdate = true;
                                }
                            }
                        }
                        LOGGER.info("Skipping SCS self-jar update while mod is running: {}", entryName);
                        continue;
                    }

                    for (String modId : modIds) {
                        if (modId == null || modId.isBlank()) {
                            continue;
                        }

                        List<Path> installed = existingModsById.getOrDefault(modId, Collections.emptyList());
                        for (Path installedJar : installed) {
                            if (installedJar.equals(entryPath)) {
                                continue;
                            }
                            try {
                                if (deleteFileIfExistsWithTimeout(installedJar, "remove old mod jar " + installedJar.getFileName())) {
                                    LOGGER.info("Removed old mod jar for modId {}: {}", modId, installedJar.getFileName());
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Failed to remove old mod jar {} for modId {}", installedJar, modId, e);
                            }
                        }

                        existingModsById.put(modId, new ArrayList<>(List.of(entryPath)));
                    }

                    writeBytesWithTimeout(entryPath, jarBytes, "write mod jar " + entryName);
                    extractedFiles.add(entryName.replace('\\', '/'));
                    LOGGER.info("Extracted mods entry: {}", entryName);
                } else {
                    try (InputStream is = zipFile.getInputStream(entry)) {
                        Files.copy(is, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    extractedFiles.add(entryName.replace('\\', '/'));
                    LOGGER.info("Extracted mods entry: {}", entryName);
                }
            }
        }

        if (!modsToRemove.isEmpty()) {
            updateProcessing(progressScreen, "Removing mods...", "Applying " + MODS_REMOVE_LIST_NAME, 0, false);
            RemovalResult removal = removeModsByName(destination, modsToRemove, progressScreen);
            if (summaryExtras != null) {
                if (!removal.removed.isEmpty()) {
                    summaryExtras.add("Mods removed by list: " + String.join(", ", removal.removed));
                }
                if (!removal.missing.isEmpty()) {
                    LOGGER.info("Mods requested for removal but not found: {}", String.join(", ", removal.missing));
                }
                if (!removal.invalid.isEmpty()) {
                    summaryExtras.add("Invalid entries in " + MODS_REMOVE_LIST_NAME + ": " + String.join(", ", removal.invalid));
                }
            }
        }

        return extractedFiles;
    }
    private static Map<String, List<Path>> indexInstalledModsById(
            Path modsDirectory,
            DownloadProgressScreen progressScreen
    ) {
        Map<String, List<Path>> byId = new HashMap<>();
        if (!Files.exists(modsDirectory)) {
            return byId;
        }

        List<Path> jarPaths;
        try (var stream = Files.walk(modsDirectory)) {
            jarPaths = stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.warn("Failed to index installed mods in {}", modsDirectory, e);
            return byId;
        }

        int total = jarPaths.size();
        for (int i = 0; i < total; i++) {
            Path jarPath = jarPaths.get(i);
            int current = i + 1;

            if (current == 1 || current == total || current % 10 == 0) {
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                String detail = total > 0
                        ? String.format("%d/%d: %s", current, total, jarPath.getFileName())
                        : jarPath.getFileName().toString();
                updateProcessing(progressScreen, "Indexing installed mods...", detail, progress, total > 0);
            }

            try {
                Set<String> modIds = getModIdsFromJarFile(jarPath);
                for (String modId : modIds) {
                    if (modId == null || modId.isBlank()) {
                        continue;
                    }
                    byId.computeIfAbsent(modId, k -> new ArrayList<>()).add(jarPath);
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to read modId from installed jar: {}", jarPath, e);
            }
        }

        return byId;
    }
    private static Set<String> getModIdsFromJarFile(Path jarPath) throws Exception {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            String tomlText = readModsTomlTextFromZipFile(zipFile);
            return extractModIdsFromTomlText(tomlText);
        }
    }

    private static String readModsTomlTextFromZipFile(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
        if (entry == null) {
            entry = zipFile.getEntry("META-INF/mods.toml");
        }
        if (entry == null) {
            return null;
        }

        try (InputStream is = zipFile.getInputStream(entry)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readModsTomlTextFromJarBytes(byte[] jarBytes) throws IOException {
        try (ZipInputStream jarZip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry jarEntry;
            while ((jarEntry = jarZip.getNextEntry()) != null) {
                String name = jarEntry.getName();
                if (!jarEntry.isDirectory()
                        && ("META-INF/neoforge.mods.toml".equals(name) || "META-INF/mods.toml".equals(name))) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    jarZip.transferTo(baos);
                    return baos.toString(StandardCharsets.UTF_8);
                }
                jarZip.closeEntry();
            }
        }
        return null;
    }

    private static Set<String> extractModIdsFromTomlText(String tomlText) {
        Set<String> modIds = new HashSet<>();
        for (ModTomlEntry entry : parseModEntries(tomlText)) {
            modIds.add(entry.modId);
        }
        return modIds;
    }

    private static Map<String, String> extractModVersionsFromTomlText(String tomlText) {
        Map<String, String> versions = new HashMap<>();
        for (ModTomlEntry entry : parseModEntries(tomlText)) {
            if (entry.version != null && !entry.version.isBlank()) {
                versions.put(entry.modId, entry.version.trim());
            }
        }
        return versions;
    }

    private static List<ModTomlEntry> parseModEntries(String tomlText) {
        if (tomlText == null || tomlText.isBlank()) {
            return Collections.emptyList();
        }

        List<ModTomlEntry> entries = new ArrayList<>();
        boolean inModsSection = false;
        String currentModId = null;
        String currentVersion = null;

        for (String rawLine : tomlText.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("[[") && line.endsWith("]]")) {
                if (inModsSection) {
                    addModTomlEntry(entries, currentModId, currentVersion);
                }
                String normalizedSection = line.replaceAll("\\s+", "");
                inModsSection = "[[mods]]".equalsIgnoreCase(normalizedSection);
                currentModId = null;
                currentVersion = null;
                continue;
            }

            if (!inModsSection) {
                continue;
            }

            String parsedModId = parseQuotedAssignmentValue(line, "modId");
            if (parsedModId != null) {
                currentModId = parsedModId;
                continue;
            }

            String parsedVersion = parseQuotedAssignmentValue(line, "version");
            if (parsedVersion != null) {
                currentVersion = parsedVersion;
            }
        }

        if (inModsSection) {
            addModTomlEntry(entries, currentModId, currentVersion);
        }

        return entries;
    }

    private static void addModTomlEntry(List<ModTomlEntry> entries, String modId, String version) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        entries.add(new ModTomlEntry(modId.toLowerCase(Locale.ROOT), version));
    }

    private static String parseQuotedAssignmentValue(String line, String expectedKey) {
        Matcher matcher = TOML_ASSIGNMENT_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        String key = matcher.group(1);
        if (!expectedKey.equalsIgnoreCase(key)) {
            return null;
        }

        return unescapeTomlBasicString(matcher.group(2)).trim();
    }

    private static String unescapeTomlBasicString(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    private static final class ModTomlEntry {
        private final String modId;
        private final String version;

        private ModTomlEntry(String modId, String version) {
            this.modId = modId;
            this.version = version;
        }
    }

    private static UpdateSummary buildUpdateSummary(
            String updateBaseUrl,
            UpdateOutcome modsOutcome,
            UpdateOutcome configOutcome,
            boolean failedEarly,
            List<String> summaryExtras
    ) {
        List<String> summaryLines = new ArrayList<>();
        List<String> detailLines = new ArrayList<>();
        boolean configAttempted = Config.updateConfig;
        boolean modsSuccess = modsOutcome != null && modsOutcome.isSuccess();
        boolean configSuccess = !configAttempted || (configOutcome != null && configOutcome.isSuccess());
        boolean modsChanged = modsOutcome != null && modsOutcome.hasChanges();
        boolean configChanged = configAttempted && configOutcome != null && configOutcome.hasChanges();

        String modsLabel = tr("screen.scs.label.mods");
        String configLabel = tr("screen.scs.label.config");
        String title = failedEarly ? tr("screen.scs.update_failed") : tr("screen.scs.update_complete");

        if (modsSuccess) {
            summaryLines.add(buildSummaryLine(modsLabel, modsOutcome.getDiff()));
            detailLines.add(buildDetailMessage(modsLabel, modsOutcome.getDiff()));
        } else {
            summaryLines.add(tr("screen.scs.mods_update_failed", updateBaseUrl));
            detailLines.add(tr("screen.scs.mods_update_failed", updateBaseUrl));
            title = tr("screen.scs.update_failed");
        }

        if (configAttempted) {
            if (configSuccess) {
                summaryLines.add(buildSummaryLine(configLabel, configOutcome.getDiff()));
                detailLines.add(buildDetailMessage(configLabel, configOutcome.getDiff()));
            } else {
                summaryLines.add(tr("screen.scs.config_update_failed", updateBaseUrl));
                detailLines.add(tr("screen.scs.config_update_failed", updateBaseUrl));
                title = tr("screen.scs.update_failed");
            }
        } else {
            summaryLines.add(tr("screen.scs.config_updates_disabled"));
        }

        if (modsChanged) {
            summaryLines.add(tr("screen.scs.mods_restart_required"));
        } else if (!modsChanged && !configChanged && !failedEarly && modsSuccess && configSuccess) {
            summaryLines.add(tr("screen.scs.no_updates_found"));
        }

        if (summaryExtras != null && !summaryExtras.isEmpty()) {
            summaryLines.add(tr("screen.scs.warnings_count", summaryExtras.size()));
            detailLines.addAll(summaryExtras);
        }

        return new UpdateSummary(title, summaryLines, detailLines);
    }

    private static String buildSummaryLine(String label, Checksum.ChecksumDiff diff) {
        if (diff == null || diff.isEmpty()) {
            return tr("screen.scs.summary_no_changes", label);
        }

        return tr("screen.scs.summary_updated_short", label, diff.getAdded().size(), diff.getModified().size(), diff.getRemoved().size());
    }

    private static String buildDetailMessage(String label, Checksum.ChecksumDiff diff) {
        if (diff == null || diff.isEmpty()) {
            return tr("screen.scs.summary_no_changes", label);
        }

        String summary = tr("screen.scs.summary_updated_long", label, diff.getAdded().size(), diff.getModified().size(), diff.getRemoved().size());

        String details = buildChangeDetails(diff);
        return details.isEmpty() ? summary : summary + " " + details;
    }

    private static String buildChangeDetails(Checksum.ChecksumDiff diff) {
        List<String> sections = new ArrayList<>();

        String added = formatChangeSection(tr("screen.scs.summary_section_added"), diff.getAdded());
        if (!added.isEmpty()) {
            sections.add(added);
        }

        String modified = formatChangeSection(tr("screen.scs.summary_section_modified"), diff.getModified());
        if (!modified.isEmpty()) {
            sections.add(modified);
        }

        String removed = formatChangeSection(tr("screen.scs.summary_section_removed"), diff.getRemoved());
        if (!removed.isEmpty()) {
            sections.add(removed);
        }

        return sections.isEmpty() ? "" : String.join(" | ", sections);
    }

    private static String formatChangeSection(String label, List<String> items) {
        if (items.isEmpty()) {
            return "";
        }

        int showCount = Math.min(items.size(), MAX_CHANGE_LIST_ITEMS);
        List<String> shown = items.subList(0, showCount);
        String text = tr("screen.scs.summary_section_format", label, String.join(", ", shown));

        if (items.size() > showCount) {
            text += " " + tr("screen.scs.summary_more", (items.size() - showCount));
        }

        return text;
    }

    private static void sendPlayerMessages(Minecraft minecraft, List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        minecraft.execute(() -> {
            if (minecraft.player == null) {
                return;
            }
            for (String message : messages) {
                minecraft.player.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    private static void updateProcessing(
            DownloadProgressScreen progressScreen,
            String title,
            String detail,
            int progress,
            boolean hasProgress
    ) {
        if (progressScreen == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> progressScreen.updateProcessing(title, detail, progress, hasProgress));
    }

    private static boolean deleteFileIfExistsWithTimeout(Path filePath, String context) throws IOException {
        return runFileIoWithTimeout(context, () -> Files.deleteIfExists(filePath));
    }

    private static void writeBytesWithTimeout(Path filePath, byte[] bytes, String context) throws IOException {
        runFileIoWithTimeout(context, () -> {
            Files.write(filePath, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return null;
        });
    }

    private static <T> T runFileIoWithTimeout(String context, java.util.concurrent.Callable<T> ioTask) throws IOException {
        ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "scs-file-io");
            thread.setDaemon(true);
            return thread;
        });

        Future<T> future = ioExecutor.submit(ioTask);
        try {
            return future.get(FILE_IO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("Timed out while trying to " + context, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while trying to " + context, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed while trying to " + context, cause);
        } finally {
            ioExecutor.shutdownNow();
        }
    }

    private static List<String> parseModsRemovalList(ZipFile zipFile, ZipEntry entry) {
        try (InputStream is = zipFile.getInputStream(entry)) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            List<String> items = new ArrayList<>();
            Matcher matcher = MODS_REMOVE_ITEM_PATTERN.matcher(content);
            while (matcher.find()) {
                String value = unescapeTomlBasicString(matcher.group(1)).trim();
                if (!value.isBlank()) {
                    items.add(value);
                }
            }
            return items;
        } catch (Exception e) {
            LOGGER.warn("Failed to parse {}", MODS_REMOVE_LIST_NAME, e);
            return List.of();
        }
    }

    private static RemovalResult removeModsByName(
            Path modsDirectory,
            List<String> rawNames,
            DownloadProgressScreen progressScreen
    ) {
        if (rawNames == null || rawNames.isEmpty()) {
            return new RemovalResult(List.of(), List.of(), List.of());
        }

        Map<String, String> requested = new HashMap<>();
        List<String> invalid = new ArrayList<>();
        for (String name : rawNames) {
            String normalized = normalizeFileName(name);
            if (normalized.isBlank() || !normalized.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                invalid.add(name);
                continue;
            }
            requested.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }

        if (requested.isEmpty()) {
            return new RemovalResult(List.of(), List.of(), invalid);
        }

        List<Path> files;
        try (var stream = Files.walk(modsDirectory)) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (Exception e) {
            LOGGER.warn("Failed to list files for removal in {}", modsDirectory, e);
            files = List.of();
        }

        List<String> removed = new ArrayList<>();
        Set<String> removedLower = new HashSet<>();
        int total = files.size();

        for (int i = 0; i < total; i++) {
            Path path = files.get(i);
            String fileName = path.getFileName().toString();
            int current = i + 1;

            if (current == 1 || current == total || current % 10 == 0) {
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                String detail = total > 0
                        ? String.format("%d/%d: %s", current, total, fileName)
                        : fileName;
                updateProcessing(progressScreen, "Removing mods...", detail, progress, total > 0);
            }

            String key = fileName.toLowerCase(Locale.ROOT);
            if (!requested.containsKey(key)) {
                continue;
            }

            try {
                if (Files.deleteIfExists(path)) {
                    removed.add(fileName);
                    removedLower.add(key);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to remove mod {}", fileName, e);
            }
        }

        List<String> missing = requested.entrySet().stream()
                .filter(entry -> !removedLower.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());

        return new RemovalResult(removed, missing, invalid);
    }
    private static String normalizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim().replace('\\', '/');
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash >= 0) {
            trimmed = trimmed.substring(lastSlash + 1);
        }
        return trimmed.trim();
    }

    private static String getCurrentModVersion() {
        return ModList.get()
                .getModContainerById(SCS.MODID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    private static int compareVersions(String left, String right) {
        if (left == null || right == null) {
            return 0;
        }
        String a = normalizeVersion(left);
        String b = normalizeVersion(right);
        if (a.isBlank() || b.isBlank()) {
            return 0;
        }

        String[] aParts = a.split("[^0-9A-Za-z]+");
        String[] bParts = b.split("[^0-9A-Za-z]+");
        int len = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < len; i++) {
            String ap = i < aParts.length ? aParts[i] : "0";
            String bp = i < bParts.length ? bParts[i] : "0";
            boolean an = ap.chars().allMatch(Character::isDigit);
            boolean bn = bp.chars().allMatch(Character::isDigit);

            if (an && bn) {
                long av = parseLongSafe(ap);
                long bv = parseLongSafe(bp);
                if (av != bv) {
                    return Long.compare(av, bv);
                }
            } else if (an != bn) {
                return an ? 1 : -1;
            } else {
                int cmp = ap.compareToIgnoreCase(bp);
                if (cmp != 0) {
                    return cmp;
                }
            }
        }
        return 0;
    }

    private static String normalizeVersion(String version) {
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class RemovalResult {
        private final List<String> removed;
        private final List<String> missing;
        private final List<String> invalid;

        private RemovalResult(List<String> removed, List<String> missing, List<String> invalid) {
            this.removed = removed;
            this.missing = missing;
            this.invalid = invalid;
        }
    }

    private static Checksum.ChecksumDiff computeAndSaveChecksumsFromZip(
            Path zipPath,
            Path checksumFile,
            DownloadProgressScreen progressScreen,
            String displayName,
            String rootPrefixToStrip
    ) throws Exception {
        LOGGER.info("Comparing checksums...");
        updateProcessing(progressScreen, "Comparing " + displayName + " checksums...", "Scanning zip entries...", 0, false);

        Map<String, String> newChecksums = new HashMap<>();
        try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zipFile.entries());
            int total = entries.size();
            int current = 0;
            for (ZipEntry entry : entries) {
                if (entry.isDirectory()) {
                    continue;
                }
                current++;
                String entryName = normalizeZipEntryName(entry.getName(), rootPrefixToStrip);
                if (entryName.isBlank()) {
                    continue;
                }
                int progress = total > 0 ? (int) ((current * 100L) / total) : 0;
                String detail = total > 0
                        ? String.format("%d/%d: %s", current, total, entryName)
                        : entryName;
                updateProcessing(progressScreen, "Comparing " + displayName + " checksums...", detail, progress, total > 0);

                try (InputStream is = zipFile.getInputStream(entry)) {
                    newChecksums.put(entryName, Checksum.computeChecksum(is));
                }
            }
        }

        Checksum.ChecksumResult result = Checksum.compareChecksums(checksumFile, newChecksums);
        Files.createDirectories(checksumFile.getParent());
        Checksum.saveChecksums(checksumFile, result.getNewChecksums());
        return result.getDiff();
    }

    private static String normalizeZipEntryName(String entryName, String rootPrefixToStrip) {
        if (entryName == null) {
            return "";
        }
        String normalized = entryName.replace('\\', '/');
        if (rootPrefixToStrip != null && !rootPrefixToStrip.isBlank()) {
            String prefix = rootPrefixToStrip.replace('\\', '/');
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
            }
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.trim();
    }
}











