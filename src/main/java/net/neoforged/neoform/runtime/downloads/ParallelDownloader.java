package net.neoforged.neoform.runtime.downloads;

import net.neoforged.neoform.runtime.utils.FileUtil;
import net.neoforged.neoform.runtime.utils.Logger;
import net.neoforged.neoform.runtime.utils.StringUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class is capable of download a large number of files concurrently, while observing a maximum
 * concurrent download limit.
 */
public class ParallelDownloader implements AutoCloseable {
    private static final Logger LOG = Logger.create();
    private static final ThreadFactory DOWNLOAD_THREAD_FACTORY = r -> Thread.ofVirtual().name("parallel-download", 1).unstarted(r);

    private final DownloadManager downloadManager;
    private final Semaphore semaphore;
    private final ExecutorService executor;
    private final AtomicInteger downloadsDone = new AtomicInteger();
    private final AtomicInteger copiesDone = new AtomicInteger();
    private final AtomicLong bytesDownloaded = new AtomicLong();
    private final AtomicLong bytesCopied = new AtomicLong();
    private final List<Exception> errors = new ArrayList<>();
    private final Path destination;
    private final int estimatedTotal;
    private volatile List<Path> localSources = List.of();

    public ParallelDownloader(DownloadManager downloadManager, int concurrentDownloads, Path destination, int estimatedTotal) {
        this.downloadManager = downloadManager;
        this.destination = destination;
        this.estimatedTotal = estimatedTotal;
        if (concurrentDownloads < 1) {
            throw new IllegalStateException("Cannot set concurrent downloads to less than 1: " + concurrentDownloads);
        }

        semaphore = new Semaphore(concurrentDownloads);
        executor = Executors.newThreadPerTaskExecutor(DOWNLOAD_THREAD_FACTORY);
    }

    public void setLocalSources(List<Path> localSources) {
        // Remove the destination if it's present in the local sources
        if (localSources.contains(destination)) {
            localSources = new ArrayList<>(localSources);
            localSources.remove(destination);
        }
        this.localSources = localSources;
    }

    public void submitDownload(DownloadSpec spec, String relativeDesination) {
        var objectLocation = destination.resolve(relativeDesination);
        executor.execute(() -> {
            boolean hasAcquired = false;
            try {
                semaphore.acquire();
                hasAcquired = true;

                // Check if the object may exist already
                for (var localSource : localSources) {
                    var existingFile = localSource.resolve(relativeDesination);
                    if (Files.isRegularFile(existingFile) && Files.size(existingFile) == spec.size()) {
                        FileUtil.safeCopy(existingFile, objectLocation);
                        bytesCopied.addAndGet(Files.size(objectLocation));
                        copiesDone.incrementAndGet();
                        return;
                    }
                }

                if (downloadManager.download(spec, objectLocation, true)) {
                    bytesDownloaded.addAndGet(spec.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                synchronized (errors) {
                    errors.add(e);
                }
            } finally {
                if (hasAcquired) {
                    semaphore.release();
                }
                var finished = downloadsDone.incrementAndGet();
                if (finished % 100 == 0) {
                    LOG.println(finished + "/" + estimatedTotal + " downloads");
                }
            }
        });
    }

    @Override
    public void close() throws DownloadsFailedException {
        if (downloadsDone.get() > 0) {
            LOG.println("Downloaded " + downloadsDone.get() + " files with a total size of " + StringUtil.formatBytes(bytesDownloaded.get()));
        }
        if (copiesDone.get() > 0) {
            LOG.println("Copied " + copiesDone.get() + " files with a total size of " + StringUtil.formatBytes(bytesCopied.get()));
        }

        if (!errors.isEmpty()) {
            throw new DownloadsFailedException(errors);
        }
    }
}
