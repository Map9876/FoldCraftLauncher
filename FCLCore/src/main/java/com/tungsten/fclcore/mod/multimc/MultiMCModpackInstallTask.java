package com.tungsten.fclcore.mod.multimc;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.tungsten.fclcore.download.DefaultDependencyManager;
import com.tungsten.fclcore.download.GameBuilder;
import com.tungsten.fclcore.game.Arguments;
import com.tungsten.fclcore.game.DefaultGameRepository;
import com.tungsten.fclcore.game.Version;
import com.tungsten.fclcore.mod.MinecraftInstanceTask;
import com.tungsten.fclcore.mod.Modpack;
import com.tungsten.fclcore.mod.ModpackConfiguration;
import com.tungsten.fclcore.mod.ModpackInstallTask;
import com.tungsten.fclcore.task.Task;
import com.tungsten.fclcore.util.gson.JsonUtils;
import com.tungsten.fclcore.util.io.CompressingUtils;
import com.tungsten.fclcore.util.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class MultiMCModpackInstallTask extends Task<Void> {

    private final File zipFile;
    private final Modpack modpack;
    private final MultiMCInstanceConfiguration manifest;
    private final String name;
    private final DefaultGameRepository repository;
    private final List<Task<?>> dependencies = new ArrayList<>(1);
    private final List<Task<?>> dependents = new ArrayList<>(4);

    public MultiMCModpackInstallTask(DefaultDependencyManager dependencyManager, File zipFile, Modpack modpack, MultiMCInstanceConfiguration manifest, String name) {
        this.zipFile = zipFile;
        this.modpack = modpack;
        this.manifest = manifest;
        this.name = name;
        this.repository = dependencyManager.getGameRepository();

        File json = repository.getModpackConfiguration(name);
        if (repository.hasVersion(name) && !json.exists())
            throw new IllegalArgumentException("Version " + name + " already exists.");

        GameBuilder builder = dependencyManager.gameBuilder().name(name).gameVersion(manifest.getGameVersion());

        if (manifest.getMmcPack() != null) {
            Optional<MultiMCManifest.MultiMCManifestComponent> forge = manifest.getMmcPack().getComponents().stream().filter(e -> e.getUid().equals("net.minecraftforge")).findAny();
            forge.ifPresent(c -> {
                if (c.getVersion() != null)
                    builder.version("forge", c.getVersion());
            });

            Optional<MultiMCManifest.MultiMCManifestComponent> neoForge = manifest.getMmcPack().getComponents().stream().filter(e -> e.getUid().equals("net.neoforged")).findAny();
            neoForge.ifPresent(c -> {
                if (c.getVersion() != null)
                    builder.version("neoforge", c.getVersion());
            });

            Optional<MultiMCManifest.MultiMCManifestComponent> liteLoader = manifest.getMmcPack().getComponents().stream().filter(e -> e.getUid().equals("com.mumfrey.liteloader")).findAny();
            liteLoader.ifPresent(c -> {
                if (c.getVersion() != null)
                    builder.version("liteloader", c.getVersion());
            });

            Optional<MultiMCManifest.MultiMCManifestComponent> fabric = manifest.getMmcPack().getComponents().stream().filter(e -> e.getUid().equals("net.fabricmc.fabric-loader")).findAny();
            fabric.ifPresent(c -> {
                if (c.getVersion() != null)
                    builder.version("fabric", c.getVersion());
            });

            Optional<MultiMCManifest.MultiMCManifestComponent> quilt = manifest.getMmcPack().getComponents().stream().filter(e -> e.getUid().equals("org.quiltmc.quilt-loader")).findAny();
            quilt.ifPresent(c -> {
                if (c.getVersion() != null)
                    builder.version("quilt", c.getVersion());
            });
        }

        dependents.add(builder.buildAsync());
        onDone().register(event -> {
            if (event.isFailed())
                repository.removeVersionFromDisk(name);
        });
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() throws Exception {
        File run = repository.getRunDirectory(name);
        File json = repository.getModpackConfiguration(name);

        ModpackConfiguration<MultiMCInstanceConfiguration> config = null;
        try {
            if (json.exists()) {
                config = JsonUtils.GSON.fromJson(FileUtils.readText(json), new TypeToken<ModpackConfiguration<MultiMCInstanceConfiguration>>() {
                }.getType());

                if (!MultiMCModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException("Version " + name + " is not a MultiMC modpack. Cannot update this version.");
            }
        } catch (JsonParseException | IOException ignore) {
        }

        String subDirectory;

        try (FileSystem fs = CompressingUtils.readonly(zipFile.toPath()).setEncoding(modpack.getEncoding()).build()) {
            // /.minecraft
            if (Files.exists(fs.getPath("/.minecraft"))) {
                subDirectory = "/.minecraft";
                // /minecraft
            } else if (Files.exists(fs.getPath("/minecraft"))) {
                subDirectory = "/minecraft";
                // /[name]/.minecraft
            } else if (Files.exists(fs.getPath("/" + manifest.getName() + "/.minecraft"))) {
                subDirectory = "/" + manifest.getName() + "/.minecraft";
                // /[name]/minecraft
            } else if (Files.exists(fs.getPath("/" + manifest.getName() + "/minecraft"))) {
                subDirectory = "/" + manifest.getName() + "/minecraft";
            } else {
                subDirectory = "/" + manifest.getName() + "/.minecraft";
            }
        }

        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), Collections.singletonList(subDirectory), any -> true, config).withStage("fcl.modpack"));
        dependents.add(new MinecraftInstanceTask<>(zipFile, modpack.getEncoding(), Collections.singletonList(subDirectory), manifest, MultiMCModpackProvider.INSTANCE, manifest.getName(), null, repository.getModpackConfiguration(name)).withStage("fcl.modpack"));
    }

    @Override
    public List<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public void execute() throws Exception {
        Version version = repository.readVersionJson(name);

        // 动态获取路径
        String gameDirectory = repository.getRunDirectory(name).getAbsolutePath();
        String javaAgentPath = getJavaAgentPathFromInstanceConfig(gameDirectory);
        String packwizTomlUrl = getPackwizTomlUrlFromInstanceConfig(gameDirectory);
        String javaAgentArgument = "-javaagent:" + javaAgentPath + "=" + packwizTomlUrl;

        try (FileSystem fs = CompressingUtils.readonly(zipFile.toPath()).setAutoDetectEncoding(true).build()) {
            Path root = MultiMCModpackProvider.getRootPath(fs.getPath("/"));
            Path patches = root.resolve("patches");

            if (Files.exists(patches)) {
                try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(patches)) {
                    for (Path patchJson : directoryStream) {
                        if (patchJson.toString().endsWith(".json")) {
                            // If json is malformed, we should stop installing this modpack instead of skipping it.
                            MultiMCInstancePatch multiMCPatch = JsonUtils.GSON.fromJson(FileUtils.readText(patchJson), MultiMCInstancePatch.class);

                            List<String> arguments = new ArrayList<>();
                            for (String arg : multiMCPatch.getTweakers()) {
                                arguments.add("--tweakClass");
                                arguments.add(arg);
                            }

                            // 添加 -javaagent 参数
                            arguments.add(javaAgentArgument);

                            Version patch = new Version(multiMCPatch.getName(), multiMCPatch.getVersion(), 1, new Arguments().addGameArguments(arguments), multiMCPatch.getMainClass(), multiMCPatch.getLibraries());
                            version = version.addPatch(patch);
                        }
                    }
                }
            }

            Path libraries = root.resolve("libraries");
            if (Files.exists(libraries))
                FileUtils.copyDirectory(libraries, repository.getVersionRoot(name).toPath().resolve("libraries"));

            Path jarmods = root.resolve("jarmods");
            if (Files.exists(jarmods))
                FileUtils.copyDirectory(jarmods, repository.getVersionRoot(name).toPath().resolve("jarmods"));
        }

        dependencies.add(repository.saveAsync(version));
    }

    /**
     * 从 instance.cfg 获取 pack.toml URL 的方法
     */
    private String getPackwizTomlUrlFromInstanceConfig(String gameDirectory) throws IOException {
        Path instanceConfigPath = Paths.get(gameDirectory, "instance.cfg");
        List<String> lines = Files.readAllLines(instanceConfigPath);
        for (String line : lines) {
            if (line.startsWith("JvmArgs=")) {
                String jvmArgs = line.substring(line.indexOf('=') + 1);
                String[] args = jvmArgs.split("=");
                return args[1];
            }
        }
        throw new IllegalStateException("pack.toml URL not found in instance.cfg");
    }

    /**
     * 从 instance.cfg 获取 packwiz-installer-bootstrap.jar 的路径
     */
    private String getJavaAgentPathFromInstanceConfig(String gameDirectory) throws IOException {
        Path instanceConfigPath = Paths.get(gameDirectory, "instance.cfg");
        List<String> lines = Files.readAllLines(instanceConfigPath);
        for (String line : lines) {
            if (line.startsWith("JvmArgs=")) {
                String jvmArgs = line.substring(line.indexOf('=') + 1);
                String[] args = jvmArgs.split("=");
                return gameDirectory + "/" + args[0].split(":")[1];
            }
        }
        throw new IllegalStateException("packwiz-installer-bootstrap.jar path not found in instance.cfg");
    }

    /**
     * 更新 modpack.cfg 文件中的 jvmArgs 参数
     */
    private void updateModpackCfg(String gameDirectory, String javaAgentArgument) throws IOException {
        Path modpackCfgPath = Paths.get(gameDirectory, "modpack.cfg");
        List<String> lines = Files.readAllLines(modpackCfgPath);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"jvmArgs\"")) {
                lines.set(i, "    \"jvmArgs\": \"" + javaAgentArgument + "\",");
                break;
            }
        }
        Files.write(modpackCfgPath, lines);
    }
        }
