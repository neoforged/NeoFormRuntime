package net.neoforged.neoforminabox.cli;

import net.neoforged.neoforminabox.utils.HashingUtil;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class LockManager implements AutoCloseable {
    private final Path lockDirectory;

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

        FileLock fileLock;
        while (true) {
            try {
                fileLock = channel.tryLock();
                if (fileLock != null) {
                    break;
                }
            } catch (IOException e) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
                throw new RuntimeException(e);
            }

            System.out.println("Waiting for lock on " + key);
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        System.out.println("Acquired lock for " + key);
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
}
