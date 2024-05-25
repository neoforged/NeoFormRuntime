package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.utils.AnsiColor;
import net.neoforged.neoform.runtime.utils.HashingUtil;
import net.neoforged.neoform.runtime.utils.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LockManager implements AutoCloseable {
    private static final Logger LOG = Logger.create();

    private final Path lockDirectory;
    private boolean verbose;

    public LockManager(Path lockDirectory) throws IOException {
        Files.createDirectories(lockDirectory);
        this.lockDirectory = lockDirectory;
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    private Path getLockFile(String key) {
        return lockDirectory.resolve("_" + HashingUtil.sha1(key) + ".lock");
    }

    public Lock lock(String key) {
        var lockFile = getLockFile(key);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to obtain lock for " + key, e);
        }

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

    @Override
    public void close() {

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
