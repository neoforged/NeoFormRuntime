package net.neoforged.neoform.runtime.cli;

import net.neoforged.neoform.runtime.utils.HashingUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Maintains a cached view of file hashes. We use SHA-1, since Minecraft uses those in their version manifest,
 * and we can reuse them.
 */
public class FileHashService {
    private final Map<Path, String> hashValues = new HashMap<>();
    private final ConcurrentHashMap<Path, Lock> locks = new ConcurrentHashMap<>();

    public String getHashValue(Path path) throws IOException {
        var lock = locks.computeIfAbsent(path, ignored -> new ReentrantLock());
        try {
            lock.lock();

            var hashValue = hashValues.get(path);
            if (hashValue == null) {
                if (Files.isDirectory(path)) {
                    hashValue = HashingUtil.hashDirectory(path, "SHA1");
                } else {
                    hashValue = HashingUtil.hashFile(path, "SHA1");
                }
                hashValues.put(path, hashValue);
            }
            return hashValue;
        } finally {
            lock.unlock();
        }
    }

    public void setHashValue(Path path, String hash) {
        var lock = locks.computeIfAbsent(path, ignored -> new ReentrantLock());
        try {
            lock.lock();

            hashValues.put(path, hash);
        } finally {
            lock.unlock();
        }
    }
}
