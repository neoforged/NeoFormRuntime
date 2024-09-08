package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Injects additional content from {@linkplain InjectSource configurable sources} into a Zip (or Jar) file.
 */
public class InjectZipContentAction extends BuiltInAction {
    private List<InjectSource> injectedSources;

    public InjectZipContentAction(List<InjectSource> injectedSources) {
        this.injectedSources = new ArrayList<>(injectedSources);
    }

    public List<InjectSource> getInjectedSources() {
        return injectedSources;
    }

    public void setInjectedSources(List<InjectSource> injectedSources) {
        this.injectedSources = new ArrayList<>(Objects.requireNonNull(injectedSources));
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var inputZipFile = environment.getRequiredInputPath("input");
        var outputZipFile = environment.getOutputPath("output");

        String packageInfoTemplateContent = findPackageInfoTemplate(injectedSources);

        try (var fileOut = Files.newOutputStream(outputZipFile);
             var zos = new ZipOutputStream(fileOut)) {

            copyInputZipContent(inputZipFile, zos, packageInfoTemplateContent);

            // Copy over the injection sources
            for (InjectSource injectedSource : injectedSources) {
                injectedSource.copyTo(zos);
            }
        }
    }

    /*
     * We support automatically adding package-info.java files to the source jar based on a template-file
     * found in any one of the inject directories.
     */
    @Nullable
    private String findPackageInfoTemplate(List<InjectSource> injectedSources) throws IOException {
        // Try to find a package-info-template.java
        for (InjectSource injectedSource : injectedSources) {
            byte[] content = injectedSource.tryReadFile("package-info-template.java");
            if (content != null) {
                return new String(content, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /*
     * Copies the original ZIP content while applying the optional package-info.java transform.
     */
    private void copyInputZipContent(Path inputZipFile, ZipOutputStream zos, @Nullable String packageInfoTemplateContent) throws IOException {
        Set<String> visited = new HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(inputZipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                zos.putNextEntry(entry);
                zis.transferTo(zos);
                zos.closeEntry();

                if (packageInfoTemplateContent != null) {
                    String pkg = entry.isDirectory() && !entry.getName().endsWith("/") ? entry.getName() : entry.getName().indexOf('/') == -1 ? "" : entry.getName().substring(0, entry.getName().lastIndexOf('/'));
                    if (visited.add(pkg)) {
                        if (!pkg.startsWith("net/minecraft/") &&
                            !pkg.startsWith("com/mojang/")) {
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry(pkg + "/package-info.java"));
                        zos.write(packageInfoTemplateContent.replace("{PACKAGE}", pkg.replaceAll("/", ".")).getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);

        for (int i = 0; i < injectedSources.size(); i++) {
            var injectedSource = injectedSources.get(i);
            try {
                ck.add("injectSource[" + i + "].type", injectedSource.getClass().getName());
                ck.add("injectSource[" + i + "].cache-key", injectedSource.getCacheKey(ck.getFileHashService()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
