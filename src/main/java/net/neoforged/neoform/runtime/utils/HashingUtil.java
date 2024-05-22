package net.neoforged.neoform.runtime.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;

public final class HashingUtil {
    private HashingUtil() {
    }

    public static String sha1(String value) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        digest.update(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hashFile(Path path, String algorithm) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        try (var in = Files.newInputStream(path);
             var din = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[8192];
            while (din.read(buffer) != -1) {
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    public static String hashDirectory(Path path, String algorithm) throws IOException {
        try (var stream = Files.walk(path)) {
            var fileListing = stream.map(p -> p.relativize(path))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .map(p -> {
                        try {
                            return p + " " + HashingUtil.hashFile(p, algorithm);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
            return sha1(fileListing);
        }
    }
}
