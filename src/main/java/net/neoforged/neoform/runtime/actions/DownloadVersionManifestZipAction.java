package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.cache.CacheKeyBuilder;
import net.neoforged.neoform.runtime.downloads.DownloadManager;
import net.neoforged.neoform.runtime.downloads.DownloadSpec;
import net.neoforged.neoform.runtime.engine.ProcessingEnvironment;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.zip.ZipFile;

public class DownloadVersionManifestZipAction extends BuiltInAction {
    private final DownloadManager downloadManager;
    private final URI manifestZipUri;

    public DownloadVersionManifestZipAction(DownloadManager downloadManager, URI manifestZipUri) {
        this.downloadManager = downloadManager;
        this.manifestZipUri = manifestZipUri;
    }

    @Override
    public void run(ProcessingEnvironment environment) throws IOException, InterruptedException {
        var manifestZipPath = environment.getWorkspace().resolve("manifest.zip");
        downloadManager.download(DownloadSpec.of(manifestZipUri), manifestZipPath);

        boolean entryFound = false;
        try (var zipFile = new ZipFile(manifestZipPath.toFile())) {
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().endsWith(".json")) {
                    if (entryFound) {
                        throw new IllegalStateException("Manifest ZIP downloaded from " + manifestZipUri + " contains more than one JSON file");
                    }
                    entryFound = true;
                    try (var in = zipFile.getInputStream(entry)) {
                        Files.copy(in, environment.getOutputPath("output"));
                    }
                }
            }
        }

        if (!entryFound) {
            throw new IllegalStateException("Manifest ZIP downloaded from " + manifestZipUri + " does not contain any JSON files");
        }
    }

    @Override
    public void computeCacheKey(CacheKeyBuilder ck) {
        super.computeCacheKey(ck);
        ck.add("download uri", manifestZipUri.toString());
    }
}
