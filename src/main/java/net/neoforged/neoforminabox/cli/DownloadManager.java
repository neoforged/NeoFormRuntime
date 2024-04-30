package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.utils.FileUtil;
import net.neoforged.neoforminabox.utils.HashingUtil;

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
    private static final String USER_AGENT = "NeoFormInABox";
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
        var url = spec.uri();
        System.out.println("Downloading " + url);

        var request = HttpRequest.newBuilder(url)
                .header("User-Agent", USER_AGENT)
                .build();

        var partialFile = finalLocation.resolveSibling(finalLocation.getFileName() + ".dltmp");
        Files.createDirectories(partialFile.getParent());
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

        var checksumAlgorithm = spec.checksumAlgorithm();
        var checksum = spec.checksum();
        if (checksumAlgorithm != null && checksum != null) {
            var fileChecksum = HashingUtil.hashFile(partialFile, checksumAlgorithm);
            if (!checksum.equalsIgnoreCase(fileChecksum)) {
                throw new IOException("Downloaded file has unexpected checksum. (actual: " + fileChecksum + ", expected: " + checksum + ")");
            }
        }

        FileUtil.atomicMove(partialFile, finalLocation);
    }
}
