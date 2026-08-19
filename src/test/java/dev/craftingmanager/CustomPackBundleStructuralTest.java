package dev.craftingmanager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

class CustomPackBundleStructuralTest {
    private static final String[] EXAMPLE_MODELS = {
            "iron_input", "coal_fuel", "alloy_output", "start_process"
    };

    @Test void settingsUsesLocalCustomPackCompositeBuild() throws IOException {
        String settings = Files.readString(repoRoot().resolve("settings.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("includeBuild(\"../custompack\")")
                        || settings.contains("includeBuild(\"../custompack/\")"),
                "settings.gradle.kts must includeBuild sibling custompack");
        String build = Files.readString(repoRoot().resolve("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(build.contains("dev.custompack.bundle"),
                "build.gradle.kts must apply dev.custompack.bundle");
    }

    @Test void custompackJsonDeclaresCraftingManagerOwnerAndNamespace() throws IOException {
        String json = Files.readString(repoRoot().resolve("custompack.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"pluginId\""), "custompack.json must set pluginId");
        assertTrue(json.contains("CraftingManager"), "pluginId must be CraftingManager");
        assertTrue(json.contains("\"namespace\""), "custompack.json must set namespace");
        assertTrue(json.contains("craftingmanager"), "namespace must be craftingmanager");
    }

    @Test void pluginSoftDependsOnCustomPack() throws IOException {
        String descriptor = Files.readString(
                repoRoot().resolve("src/main/resources/plugin.yml"), StandardCharsets.UTF_8);
        assertTrue(descriptor.contains("CustomPack"), "plugin.yml must declare CustomPack");
        assertTrue(descriptor.contains("softdepend") || descriptor.contains("depend"),
                "plugin.yml must depend or softdepend CustomPack");
    }

    @Test void exampleGuiModelsAreCustomPackItemModelSources() throws IOException {
        for (String id : EXAMPLE_MODELS) {
            Path modelDir = repoRoot().resolve("src/main/item-models").resolve(id);
            assertTrue(Files.isRegularFile(modelDir.resolve("item-model.json")),
                    "item-model.json required under item-models/" + id);
            assertTrue(Files.isRegularFile(modelDir.resolve("layer0.png")),
                    "layer0.png texture required for " + id);
            String model = Files.readString(modelDir.resolve("item-model.json"), StandardCharsets.UTF_8);
            assertTrue(model.contains("\"" + id + "\""), "item-model id must be " + id);
            assertTrue(model.contains("generated") || model.contains("handheld") || model.contains("geometry"),
                    "item-model must declare a supported kind");
        }
        assertFalse(Files.exists(repoRoot().resolve("src/main/resources/assets")),
                "resource-pack assets must not live under src/main/resources");
        assertFalse(Files.exists(repoRoot().resolve("src/main/custompack/assets")),
                "compiled item models must come from src/main/item-models, not hand-laid pack files");
    }

    @Test void productionJarEmbedsCompiledCustomPackModels() throws IOException {
        Path jarPath = productionJar();
        assertTrue(Files.isRegularFile(jarPath), "production JAR missing: " + jarPath);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            assertNotNull(jar.getEntry("META-INF/custompack/bundle.json"),
                    "META-INF/custompack/bundle.json must be embedded");
            assertNotNull(jar.getEntry("META-INF/custompack/catalog.json"),
                    "META-INF/custompack/catalog.json must be embedded");
            String bundle = readEntry(jar, "META-INF/custompack/bundle.json");
            assertTrue(bundle.contains("\"ownerPluginId\":\"CraftingManager\"")
                            || bundle.contains("\"ownerPluginId\": \"CraftingManager\""),
                    "ownerPluginId must equal CraftingManager, was:\n" + bundle);
            String catalog = readEntry(jar, "META-INF/custompack/catalog.json");
            for (String id : EXAMPLE_MODELS) {
                assertTrue(catalog.contains("craftingmanager:" + id),
                        "catalog must list craftingmanager:" + id + ", was:\n" + catalog);
                assertNotNull(jar.getEntry(
                                "META-INF/custompack/content/assets/craftingmanager/items/" + id + ".json"),
                        "compiled items definition missing for " + id);
                assertNotNull(jar.getEntry(
                                "META-INF/custompack/content/assets/craftingmanager/models/item/" + id + ".json"),
                        "compiled model JSON missing for " + id);
                assertNotNull(jar.getEntry(
                                "META-INF/custompack/content/assets/craftingmanager/textures/item/" + id + ".png"),
                        "compiled texture PNG missing for " + id);
            }
        }
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("settings.gradle.kts"))) return cwd;
        if (Files.isRegularFile(cwd.resolve("../settings.gradle.kts"))) return cwd.resolve("..").normalize();
        throw new IllegalStateException("Cannot locate repo root from " + cwd);
    }

    private static Path productionJar() throws IOException {
        Path libs = repoRoot().resolve("build/libs");
        assertTrue(Files.isDirectory(libs), "build/libs missing — run assemble first");
        try (Stream<Path> stream = Files.list(libs)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jar")
                                && !name.contains("-sources")
                                && !name.contains("-javadoc")
                                && !name.contains("-custompack");
                    })
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("assemble must produce a production JAR under build/libs"));
        }
    }

    private static String readEntry(JarFile jar, String path) throws IOException {
        ZipEntry entry = jar.getEntry(path);
        assertNotNull(entry, "missing JAR entry: " + path);
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
