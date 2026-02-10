package com.scs.core;

import com.moandjiezana.toml.Toml;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.stream.Collectors;

public final class RegisterCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterCommands.class);
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private static final Path MODS_FOLDER = Path.of("mods");
    private static final Path CONFIG_FOLDER = Path.of("config");
    private static final Path SHARED_FILES_FOLDER = Path.of("SCS/shared-files");
    private static final Path MODS_ZIP = SHARED_FILES_FOLDER.resolve("mods.zip");
    private static final Path CONFIG_ZIP = SHARED_FILES_FOLDER.resolve("config.zip");

    private static FileTime lastBuildTime = FileTime.fromMillis(0);
    private static FileTime lastConfigBuildTime = FileTime.fromMillis(0);

    private RegisterCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LOGGER.info("Registering /scs commands...");

        dispatcher.register(Commands.literal("scs")
                .then(Commands.literal("save-mods")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            saveModsToZip();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Zipping mods... check console for progress."),
                                    true
                            );
                            return 1;
                        })
                )
                .then(Commands.literal("save-config")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            saveConfigToZip();
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Zipping config... check console for progress."),
                                    true
                            );
                            return 1;
                        })
                )
        );
    }

    public static void saveModsToZip() {
        EXECUTOR.execute(RegisterCommands::buildModsZip);
    }

    public static void saveConfigToZip() {
        EXECUTOR.execute(RegisterCommands::buildConfigZip);
    }

    private static void buildModsZip() {
        try {
            List<Path> modFiles = collectFiles(MODS_FOLDER, path -> path.toString().endsWith(".jar"));
            if (modFiles.isEmpty()) {
                LOGGER.warn("No .jar files found in mods folder, skipping zip.");
                return;
            }

            FileTime latestChange = findLatestChange(modFiles);
            if (shouldSkipZipBuild(latestChange, lastBuildTime, MODS_ZIP)) {
                LOGGER.info("Mods have not changed since last build. Skipping zip creation.");
                return;
            }

            LOGGER.info("Starting mods.zip creation. Found {} mods.", modFiles.size());
            if (!ensureParentExists(MODS_ZIP)) {
                return;
            }

            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(MODS_ZIP))) {
                int total = modFiles.size();
                int index = 0;

                for (Path path : modFiles) {
                    index++;
                    try {
                        String modName = getModNameFromJar(path);
                        Path relativePath = MODS_FOLDER.relativize(path);
                        ZipEntry zipEntry = new ZipEntry(relativePath.toString());
                        zipOut.putNextEntry(zipEntry);
                        Files.copy(path, zipOut);
                        zipOut.closeEntry();

                        LOGGER.info("[{}/{}] Included mod: {} ({})",
                                index, total, modName, path.getFileName());
                    } catch (Exception e) {
                        LOGGER.error("Failed to process mod: {}", path, e);
                    }
                }
            }

            lastBuildTime = latestChange;
            LOGGER.info("Finished creating mods.zip in shared-files. {} mods processed.", modFiles.size());
        } catch (IOException e) {
            LOGGER.error("Failed to create mods.zip", e);
        }
    }

    private static void buildConfigZip() {
        try {
            if (!Files.exists(CONFIG_FOLDER)) {
                LOGGER.warn("Config folder does not exist, skipping zip.");
                return;
            }

            List<Path> configFiles = collectFiles(CONFIG_FOLDER, Files::isRegularFile);
            if (configFiles.isEmpty()) {
                LOGGER.warn("No files found in config folder, skipping zip.");
                return;
            }

            FileTime latestChange = findLatestChange(configFiles);
            if (shouldSkipZipBuild(latestChange, lastConfigBuildTime, CONFIG_ZIP)) {
                LOGGER.info("Config has not changed since last build. Skipping zip creation.");
                return;
            }

            LOGGER.info("Starting config.zip creation. Found {} files.", configFiles.size());
            if (!ensureParentExists(CONFIG_ZIP)) {
                return;
            }

            try (ZipOutputStream zipOut = new ZipOutputStream(Files.newOutputStream(CONFIG_ZIP))) {
                int total = configFiles.size();
                int index = 0;

                for (Path path : configFiles) {
                    index++;
                    Path relativePath = CONFIG_FOLDER.relativize(path);
                    String entryName = relativePath.toString().replace('\\', '/');
                    ZipEntry zipEntry = new ZipEntry(entryName);
                    zipOut.putNextEntry(zipEntry);
                    Files.copy(path, zipOut);
                    zipOut.closeEntry();

                    LOGGER.info("[{}/{}] Included config file: {}", index, total, relativePath);
                }
            }

            lastConfigBuildTime = latestChange;
            LOGGER.info("Finished creating config.zip in shared-files. {} files processed.", configFiles.size());
        } catch (IOException e) {
            LOGGER.error("Failed to create config.zip", e);
        }
    }

    private static List<Path> collectFiles(Path root, Predicate<Path> filter) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(filter)
                    .collect(Collectors.toList());
        }
    }

    private static FileTime findLatestChange(List<Path> paths) {
        return paths.stream()
                .map(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (IOException e) {
                        return FileTime.fromMillis(0);
                    }
                })
                .max(FileTime::compareTo)
                .orElse(FileTime.fromMillis(0));
    }

    private static boolean shouldSkipZipBuild(FileTime latestChange, FileTime lastBuild, Path zipPath) {
        return latestChange.compareTo(lastBuild) <= 0 && Files.exists(zipPath);
    }

    private static boolean ensureParentExists(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to create directories for {}", path.getFileName(), e);
            return false;
        }
    }

    private static String getModNameFromJar(Path jarPath) {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {

            String forgeName = readDisplayNameFromModsToml(zipFile, "META-INF/mods.toml");
            if (forgeName != null) {
                return forgeName;
            }

            ZipEntry fabricModJson = zipFile.getEntry("fabric.mod.json");
            if (fabricModJson != null) {
                try (InputStream is = zipFile.getInputStream(fabricModJson)) {
                    String jsonContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    JSONObject json = new JSONObject(jsonContent);
                    String name = json.optString("name", "").trim();
                    if (!name.isBlank()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to read metadata from {}. Using file name fallback.", jarPath, e);
            return jarPath.getFileName().toString();
        }
        return jarPath.getFileName().toString();
    }

    private static String readDisplayNameFromModsToml(ZipFile zipFile, String entryName) {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            return null;
        }

        try (InputStream is = zipFile.getInputStream(entry)) {
            Toml toml = new Toml().read(is);
            String rootDisplayName = toml.getString("display_name");
            if (rootDisplayName != null && !rootDisplayName.isBlank()) {
                return rootDisplayName;
            }

            var modsList = toml.getTables("mods");
            if (modsList != null && !modsList.isEmpty()) {
                Toml firstMod = modsList.get(0);
                String modDisplayName = firstMod.getString("displayName");
                if (modDisplayName != null && !modDisplayName.isBlank()) {
                    return modDisplayName;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to read display name from {}", entryName, e);
        }

        return null;
    }
}

