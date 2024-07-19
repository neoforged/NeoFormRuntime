package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import net.neoforged.neoform.runtime.utils.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LockManager {
    private static final Logger LOG = Logger.create();

    private final Path lockDirectory;
    private boolean verbose;

    public LockManager(Path lockDirectory) throws IOException {
        Files.createDirectories(lockDirectory);
        this.lockDirectory = lockDirectory;
    }

    private Path getLockFile(String key) {
        return lockDirectory.resolve("_" + HashingUtil.sha1(key) + ".lock");
    }

    public Lock lock(String key) {
        var lockFile = getLockFile(key);

        // We need an open FileChannel to actually get an exclusive lock on the file,
        // So we create/open the existing one here. Opening the same file by two processes will itself not
        // cause blocking.
        FileChannel channel = null;
        int attempt = 0;
        while (channel == null) {
            try {
                attempt++;
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            } catch (AccessDeniedException e) {
                if (attempt > 5) {
                    throw new RuntimeException("Failed to create lock-file " + lockFile + ": " + e.getMessage(), e);
                }
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to create lock-file " + lockFile + ": " + e.getMessage(), e);
            }
        }

        // Now that we have an open file handle (in form of a FileChannel)
        // We try to get an exclusive lock on it. If that fails, we wait 1s and try again endlessly
        Logger.IndeterminateSpinner spinner = null;
        FileLock fileLock;
        while (true) {
            try {
                fileLock = channel.tryLock();
                if (fileLock != null) {
                    break;
                }
            } catch (OverlappingFileLockException ignored) {
                // This VM currently holds the lock in another thread
            } catch (IOException e) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                throw new RuntimeException(e);
            }

            if (spinner == null) {
                spinner = LOG.spinner("Waiting for lock on " + key);
            } else {
                spinner.tick();
            }

            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                if (spinner != null) {
                    spinner.end();
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (spinner != null) {
            spinner.end();
        }

        if (verbose) {
            LOG.println(AnsiColor.MUTED + " Acquired lock for " + key + AnsiColor.RESET);
        }
        return new Lock(fileLock);
    }

    /**
     * Removes old outdated lock files.
     */
    public void performMaintenance() {
        FileTime newestToDelete = FileTime.from(Instant.now().minus(24, ChronoUnit.HOURS));

        var lockFilesDeleted = new AtomicInteger();
        try (var stream = Files.list(lockDirectory)) {
            stream.filter(f -> {
                var filename = f.getFileName().toString();
                return filename.startsWith("_") && filename.endsWith(".lock");
            }).filter(f -> {
                try {
                    var attributes = Files.readAttributes(f, BasicFileAttributes.class);
                    return attributes.isRegularFile()
                           && attributes.lastModifiedTime().compareTo(newestToDelete) < 0;
                } catch (IOException ignored) {
                    return false;
                }
            }).forEach(f -> {
                try {
                    Files.delete(f);
                    lockFilesDeleted.incrementAndGet();
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }

        if (lockFilesDeleted.get() > 0) {
            LOG.println(AnsiColor.MUTED + " Deleted " + lockFilesDeleted.get() + " outdated lock files");
        }
    }

    public static class Lock implements AutoCloseable {
        private final FileLock fileLock;

        public Lock(FileLock fileLock) {
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                fileLock.release();
            } catch (IOException ignored) {
                System.err.println("Failed to release lock on " + fileLock.channel().toString());
            }
            try {
                fileLock.channel().close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
}
