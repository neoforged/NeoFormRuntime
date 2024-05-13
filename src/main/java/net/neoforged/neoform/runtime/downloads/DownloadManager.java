package net.neoforged.neoform.runtime.downloads;

import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.HashingUtil;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DownloadManager implements AutoCloseable {
    private static final String USER_AGENT = "NeoFormRuntime";
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("download", 1).factory());

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public void close() throws Exception {
        httpClient.close();
        executor.shutdownNow();
        executor.awaitTermination(1, TimeUnit.MINUTES);
    }

    public void download(URI uri, Path finalLocation) throws IOException {
        download(new SimpleDownloadSpec(uri), finalLocation);
    }

    public void download(DownloadSpec spec, Path finalLocation) throws IOException {
        download(spec, finalLocation, false);
    }

    public boolean download(DownloadSpec spec, Path finalLocation, boolean silent) throws IOException {
        var url = spec.uri();
        if (!silent) {
            System.out.println("Downloading " + url);
        }

        // Don't re-download the file if we can avoid it
        var checksum = spec.checksum();
        var checksumAlgorithm = spec.checksumAlgorithm();
        if (checksum != null && spec.checksumAlgorithm() != null && Files.exists(finalLocation)) {
            var currentHash = HashingUtil.hashFile(finalLocation, spec.checksumAlgorithm());
            if (checksum.equalsIgnoreCase(currentHash)) {
                return false;
            }
        }

        var partialFile = finalLocation.resolveSibling(finalLocation.getFileName() + "." + Math.random() + ".dltmp");
        Files.createDirectories(partialFile.getParent());

        if (url.getScheme().equals("file")) {
            // File system download (e.g. from maven local)
            var fileInRepo = Path.of(url);
            if (!Files.exists(fileInRepo)) {
                throw new FileNotFoundException("Could not find: " + url);
            }
        }

        try {
            if (url.getScheme().equals("file")) {
                // File system download (e.g. from maven local)
                var fileInRepo = Path.of(url);
                if (!Files.exists(fileInRepo)) {
                    throw new FileNotFoundException("Could not find: " + url);
                }

                Files.copy(fileInRepo, partialFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                var request = HttpRequest.newBuilder(url)
                        .header("User-Agent", USER_AGENT)
                        .build();

                HttpResponse<Path> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(partialFile));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted", e);
                }

                if (response.statusCode() == 404) {
                    throw new FileNotFoundException(url.toString());
                } else if (response.statusCode() != 200) {
                    throw new IOException("Failed to download " + url + ": " + response.statusCode());
                }

                // Validate file
                if (spec.size() != -1) {
                    var fileSize = Files.size(partialFile);
                    if (fileSize != spec.size()) {
                        throw new IOException("Size of downloaded file has unexpected size. (actual: " + fileSize + ", expected: " + spec.size() + ")");
                    }
                }

                if (checksumAlgorithm != null && checksum != null) {
                    var fileChecksum = HashingUtil.hashFile(partialFile, checksumAlgorithm);
                    if (!checksum.equalsIgnoreCase(fileChecksum)) {
                        throw new IOException("Downloaded file has unexpected checksum. (actual: " + fileChecksum + ", expected: " + checksum + ")");
                    }
                }
            }

            FileUtil.atomicMove(partialFile, finalLocation);
        } finally {
            try {
                Files.deleteIfExists(partialFile);
            } catch (IOException e) {
                System.err.println("Failed to delete temporary download file " + partialFile);
            }
        }
        return true;
    }
}
