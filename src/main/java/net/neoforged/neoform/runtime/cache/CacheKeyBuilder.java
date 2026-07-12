package net.neoforged.neoform.runtime.cache;

import net.neoforged.neoform.runtime.cli.FileHashService;
import net.neoforged.neoform.runtime.engine.DataSource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CacheKeyBuilder {
    private final String type;
    private final FileHashService fileHashService;
    private final Map<String, DataSource> dataSources;

    private final Map<String, CacheKey.AnnotatedValue> components = new LinkedHashMap<>();

    public CacheKeyBuilder(String type, FileHashService fileHashService, Map<String, DataSource> dataSources) {
        this.type = type;
        this.fileHashService = fileHashService;
        this.dataSources = Map.copyOf(dataSources);
    }

    public void addPaths(String component, Collection<Path> resultPath) {
        add(component, resultPath.parallelStream().map(path -> {
            try {
                return new CacheKey.AnnotatedValue(fileHashService.getHashValue(path), path.toString());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).toList());
    }

    public void addPath(String component, Path path) {
        String hashValue;
        try {
            hashValue = fileHashService.getHashValue(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        add(component, hashValue, prettifyPath(path));
    }

    public void addDataSources(String component, Collection<String> dataSourceIds) {
        for (var dataSourceId : dataSourceIds) {
            addDataSource(component + "[" + dataSourceId + "]", dataSourceId);
        }
    }

    public void addDataSource(String component, String dataSourceId) {
        var dataSource = dataSources.get(dataSourceId);
        if (dataSource == null) {
            throw new IllegalArgumentException("Data source " + dataSourceId + " is not defined");
        }

        try {
            add(component, hashDataSource(dataSource), prettifyPath(dataSource.archivePath()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute hash for data source " + dataSource, e);
        }
    }

    private String hashDataSource(DataSource dataSource) throws IOException {
        var archive = dataSource.archive();
        var dataPath = dataSource.folder();
        var hasher = new ZipContentHasher(archive);

        if (!dataPath.isEmpty()) {
            var rootEntry = archive.getEntry(dataPath);
            if (rootEntry != null && !rootEntry.isDirectory()) {
                hasher.addEntry(rootEntry);
                return hasher.getHash();
            }
        }

        if (hasher.addFilteredEntriesFromPath(dataPath, entry -> !entry.isDirectory()) == 0) {
            throw new IllegalArgumentException("Data source " + dataSource + " does not exist.");
        }
        return hasher.getHash();
    }

    public static String prettifyPath(Path path) {
        // Prettify the path
        var userHome = Paths.get(System.getProperty("user.home"));
        String prettifiedPath;
        if (path.startsWith(userHome)) {
            prettifiedPath = "~/" + userHome.relativize(path).toString().replace('\\', '/');
        } else {
            prettifiedPath = path.toString();
        }
        return prettifiedPath;
    }

    public void add(String component, String text) {
        add(component, text, null);
    }

    public void add(String component, String text, @Nullable String annotation) {
        if (component == null) {
            throw new IllegalArgumentException("Cache key component cannot be null");
        }
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        components.put(component, new CacheKey.AnnotatedValue(text, annotation));
    }

    public void add(String component, CacheKey.AnnotatedValue value) {
        add(component, value.value(), value.annotation());
    }

    public void addStrings(String component, List<String> values) {
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        for (int i = 0; i < values.size(); i++) {
            add(component + "[" + i + "]", values.get(i), null);
        }
    }

    private void add(String component, List<CacheKey.AnnotatedValue> values) {
        if (component == null) {
            throw new IllegalArgumentException("Cache key component cannot be null");
        }
        if (components.containsKey(component)) {
            throw new IllegalArgumentException("Duplicate cache key component: " + component);
        }
        for (int i = 0; i < values.size(); i++) {
            components.put(component + "[" + i + "]", values.get(i));
        }
    }

    public CacheKey build() {
        return new CacheKey(type, components);
    }

    public FileHashService getFileHashService() {
        return fileHashService;
    }
}
