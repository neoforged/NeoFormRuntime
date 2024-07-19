package net.neoforged.neoform.runtime.downloads;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.SimpleFileServer;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadManagerTest {
    @TempDir
    Path tempDir;
    @TempDir
    Path remoteWebRoot;
    HttpServer server;
    String baseUrl;
    DownloadManager downloadManager = new DownloadManager();
    List<String> requests = new ArrayList<>();
    List<Integer> queuedErrors = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        InetSocketAddress addr = new InetSocketAddress(Inet4Address.getLocalHost(), 0);
        var fileHandler = SimpleFileServer.createFileHandler(remoteWebRoot);
        server = HttpServer.create(addr, 1, "/", fileHandler, new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                requests.add(exchange.getRequestURI().toString());
                if (!queuedErrors.isEmpty()) {
                    exchange.sendResponseHeaders(queuedErrors.removeFirst(), 0);
                    exchange.close();
                    return;
                }
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return "logging filter";
            }
        });

        server.start();
        var address = server.getAddress();
        baseUrl = "http://" + address.getAddress().getHostAddress() + ":" + address.getPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop(0);
        downloadManager.close();
    }

    @Test
    void testSimpleDownload() throws IOException {
        var uri = URI.create(baseUrl + "/testpath.dat");
        Files.writeString(remoteWebRoot.resolve("testpath.dat"), "hello, world!");
        var destination = tempDir.resolve("test.dat");
        downloadManager.download(uri, destination);
        assertThat(destination).hasContent("hello, world!");
    }

    /**
     * If only a URI is provided, and no length or checksum, the downloader will always re-download.
     */
    @Test
    void testSimpleDownloadUnconditionallyOverwrites() throws Exception {
        var uri = URI.create(baseUrl + "/testpath.dat");
        Files.writeString(remoteWebRoot.resolve("testpath.dat"), "hello, world!");
        var destination = tempDir.resolve("test.dat");
        downloadManager.download(uri, destination);
        downloadManager.download(uri, destination);
        assertThat(destination).hasContent("hello, world!");
        assertThat(requests).containsExactly("/testpath.dat", "/testpath.dat");
    }

    /**
     * If SHA-1 checksum and length are provided, the downloader will first check if the
     * file is already downloaded.
     */
    @Test
    void testValidExistingFilesAreNotRedownloaded() throws Exception {
        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        Files.copy(remoteFile, destination);

        assertFalse(downloadManager.download(downloadSpecFor(remoteFile), destination));
        assertThat(destination).hasContent("hello, world!");
        assertThat(requests).isEmpty();
    }

    /**
     * If SHA-1 checksum and length are provided, and the local file is corrupted,
     * it will be re-downloaded.
     */
    @Test
    void testCorruptedLocalFilesAreRedownloaded() throws Exception {
        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        var downloadSpec = downloadSpecFor(remoteFile);

        Files.writeString(destination, "CORRUPTED!");

        assertTrue(downloadManager.download(downloadSpec, destination));

        assertThat(destination).hasContent("hello, world!");
        assertThat(requests).containsExactly("/testpath.dat");
    }

    /**
     * If length is provided, the file downloaded from the remote is validated.
     */
    @Test
    void testCorruptedRemoteFilesAreRejectedBasedOnSize() throws Exception {
        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        var downloadSpec = new FullDownloadSpec(URI.create(baseUrl + "/testpath.dat"), (int) Files.size(remoteFile), null);
        Files.writeString(remoteFile, "and now it is corrupted because its size differs!!!");

        var e = assertThrows(IOException.class, () -> downloadManager.download(downloadSpec, destination));
        assertThat(e).hasMessageContaining("Downloaded file has unexpected size. (actual: 51, expected: 13)");
    }

    /**
     * If SHA-1 checksum is provided, the file downloaded from the remote is validated.
     */
    @Test
    void testCorruptedRemoteFilesAreRejectedBasedOnChecksum() throws Exception {
        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        var downloadSpec = new FullDownloadSpec(URI.create(baseUrl + "/testpath.dat"), -1, HashingUtil.sha1(remoteFile));
        Files.writeString(remoteFile, "hello, warld!");

        var e = assertThrows(IOException.class, () -> downloadManager.download(downloadSpec, destination));
        assertThat(e).hasMessageContaining("Downloaded file has unexpected checksum. (actual: decdbe49afb7782c8a07b7750097e77ecf73f437, expected: 1f09d30c707d53f3d16c530dd73d70a6ce7596a9)");
    }

    @Test
    void testStatusCodeIsRetried() throws Exception {
        queuedErrors.add(429);

        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        assertTrue(downloadManager.download(downloadSpecFor(remoteFile), destination));
        assertThat(requests).containsExactly("/testpath.dat", "/testpath.dat");
    }

    @Test
    void testRetryOnStatusCodeStopsAfterFiveTries() throws Exception {
        Collections.addAll(queuedErrors, 429, 429, 429, 429, 429, 429, 429, 429, 429, 429, 429, 429);

        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        var e = assertThrows(IOException.class, () -> downloadManager.download(downloadSpecFor(remoteFile), destination));
        assertThat(e)
                .hasMessageContaining("Failed to download")
                .hasMessageContaining("HTTP Status Code 429");
        assertThat(requests).containsExactly(
                "/testpath.dat",
                "/testpath.dat",
                "/testpath.dat",
                "/testpath.dat",
                "/testpath.dat"
        );
    }

    @Test
    void testFileNotFoundIsNotRetried() throws Exception {
        Collections.addAll(queuedErrors, 404);

        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        assertThrows(FileNotFoundException.class, () -> downloadManager.download(downloadSpecFor(remoteFile), destination));
        assertThat(requests).containsExactly("/testpath.dat");
    }

    @Test
    void testServerErrorsAreNotRetried() throws Exception {
        Collections.addAll(queuedErrors, 500, 500, 500);

        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        var destination = tempDir.resolve("test.dat");
        Files.writeString(remoteFile, "hello, world!");
        var e = assertThrows(IOException.class, () -> downloadManager.download(downloadSpecFor(remoteFile), destination));
        assertThat(e)
                .hasMessageContaining("Failed to download")
                .hasMessageContaining("HTTP Status Code 500");
        assertThat(requests).containsExactly("/testpath.dat");
    }

    @Test
    void testSupportsFileUrlDownloads() throws IOException {
        var remoteFile = remoteWebRoot.resolve("testpath.dat");
        Files.writeString(remoteFile, "hello, world!");
        var destination = tempDir.resolve("test.dat");
        downloadManager.download(remoteFile.toUri(), destination);
    }

    FullDownloadSpec downloadSpecFor(Path path) throws IOException {
        return new FullDownloadSpec(
                URI.create(baseUrl + "/" + remoteWebRoot.relativize(path).toString().replace('\\', '/')),
                (int) Files.size(path),
                HashingUtil.sha1(path)
        );
    }

    record FullDownloadSpec(URI uri, int size, String checksum) implements DownloadSpec {
        @Override
        public String checksumAlgorithm() {
            return checksum != null ? "SHA-1" : null;
        }
    }
}