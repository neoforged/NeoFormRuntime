package net.neoforged.neoform.runtime.downloads;

import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import net.neoforged.neoform.runtime.utils.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DownloadManager implements AutoCloseable {
    private static final Logger LOG = Logger.create();

    private static final String USER_AGENT = "NeoFormRuntime";
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("download", 1).factory());

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public void close() throws Exception {
        httpClient.shutdownNow();
        httpClient.close();
        executor.shutdownNow();
        if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
            LOG.println("Failed to wait for background downloads to finish.");
        }
    }

    public void download(URI uri, Path finalLocation) throws IOException {
        download(new SimpleDownloadSpec(uri), finalLocation);
    }

    public boolean download(DownloadSpec spec, Path finalLocation) throws IOException {
        return download(spec, finalLocation, false);
    }

    public boolean download(DownloadSpec spec, Path finalLocation, boolean silent) throws IOException {
        var url = spec.uri();
        if (!silent) {
            LOG.println("  ↓ " + url);
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

        try {
            if (url.getScheme().equals("file")) {
                // File system download (e.g. from maven local)
                var fileInRepo = Path.of(url);
                try {
                    Files.copy(fileInRepo, partialFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (NoSuchFileException e) {
                    // Translate the NIO exception since we handle 404 errors by throwing FileNotFoundException
                    // and callers of this method should get the same exception for 404 regardless of protocol.
                    throw new FileNotFoundException(e.getMessage());
                }
            } else {
                var request = HttpRequest.newBuilder(url)
                        .header("User-Agent", USER_AGENT)
                        .build();

                var attempts = 0;
                IOException lastError = null;
                while (attempts++ < 5) {
                    HttpResponse<Path> response;
                    try {
                        response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(partialFile));
                        lastError = null;
                    } catch (IOException e) {
                        lastError = e;
                        waitForRetry(1);
                        continue;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted", e);
                    }

                    if (response.statusCode() == 200) {
                        break;
                    } else if (response.statusCode() == 404) {
                        throw new FileNotFoundException(url.toString());
                    } else {
                        lastError = new IOException("Failed to download " + url + ": HTTP Status Code " + response.statusCode());
                        if (canRetryStatusCode(response.statusCode())) {
                            waitForRetry(response);
                            continue;
                        }
                        break;
                    }
                }

                if (lastError != null) {
                    throw lastError;
                }

                // Validate file
                if (spec.size() != -1) {
                    var fileSize = Files.size(partialFile);
                    if (fileSize != spec.size()) {
                        throw new IOException("Downloaded file has unexpected size. (actual: " + fileSize + ", expected: " + spec.size() + ")");
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

    private static void waitForRetry(HttpResponse<?> response) throws IOException {
        // We only support the version of this that specifies the delay in seconds
        var retryAfter = response.headers().firstValueAsLong("Retry-After").orElse(5);
        // Clamp some unreasonable delays to 5 minutes
        waitForRetry(Math.clamp(retryAfter, 0, 300));
    }

    private static void waitForRetry(int seconds) throws IOException {
        var waitUntil = Instant.now().plusSeconds(seconds);

        while (Instant.now().isBefore(waitUntil)) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for retry.", e);
            }
        }
    }

    private static boolean canRetryStatusCode(int statusCode) {
        return statusCode == 408 // Request timeout
               || statusCode == 425 // Too early
               || statusCode == 429 // Rate-limit exceeded
               || statusCode == 502
               || statusCode == 503
               || statusCode == 504;
    }
}
