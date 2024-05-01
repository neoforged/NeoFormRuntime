package net.neoforged.neoforminabox.engine;

import net.neoforged.neoforminabox.config.neoform.NeoFormDistConfig;
import net.neoforged.neoforminabox.config.neoform.NeoFormStep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Interpolates tokens of the form {@code {token}} found in the argument lists of NeoForm functions.
 */
public final class NeoFormInterpolator {
    public static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([^}]+)}");
    private final ProcessingEnvironment environment;
    private final NeoFormStep step;
    private final NeoFormDistConfig neoFormConfig;
    private final ZipFile neoFormArchive;

    public NeoFormInterpolator(ProcessingEnvironment environment,
                               NeoFormStep step,
                               NeoFormDistConfig neoFormConfig,
                               ZipFile neoFormArchive) {
        this.environment = environment;
        this.step = step;
        this.neoFormConfig = neoFormConfig;
        this.neoFormArchive = neoFormArchive;
    }

    public static void collectReferencedVariables(String text, Set<String> variables) {
        var matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }

    public String interpolate(String text) {
        var matcher = TOKEN_PATTERN.matcher(text);
        return matcher.replaceAll(matchResult -> Matcher.quoteReplacement(resolveRequiredInput(matchResult.group(1))));
    }

    public String resolveRequiredInput(String inputId) {
        var stepValue = step.values().get(inputId);
        if (stepValue != null) {
            // Uninterpolated constant
            if (!stepValue.contains("{")) {
                return stepValue;
            }
            return representPath(environment.getRequiredInputPath(inputId));
        }

        // We can also access data-files defined in the NeoForm archive via the `data` indirection
        var dataPath = neoFormConfig.getDataPathInZip(inputId);
        if (dataPath != null) {
            return representPath(extractData(dataPath));
        }

        return representPath(environment.getOutputPath(inputId));
    }

    private Path extractData(String dataPath) {
        var entry = neoFormArchive.getEntry(dataPath);
        if (entry == null) {
            throw new IllegalArgumentException("NeoForm archive entry " + dataPath + " does not exist.");
        }

        if (entry.getName().startsWith("/") || entry.getName().contains("..")) {
            throw new IllegalArgumentException("Unsafe ZIP path: " + entry.getName());
        }

        // Determine if an entire directory or only a file needs to be extracted
        if (entry.isDirectory()) {
            throw new UnsupportedOperationException();
        } else {
            var path = environment.getWorkspace().resolve(entry.getName());
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path.getParent());
                    try (var in = neoFormArchive.getInputStream(entry)) {
                        Files.copy(in, path);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to extract referenced NeoForm data " + dataPath + " to " + path, e);
                }
            }
            return path;
        }
    }

    private String representPath(Path path) {
        var result = environment.getWorkspace().relativize(path);
        if (result.getParent() == null) {
            // Some tooling can't deal with paths that do not have directories
            return "./" + result;
        } else {
            return result.toString();
        }
    }
}
