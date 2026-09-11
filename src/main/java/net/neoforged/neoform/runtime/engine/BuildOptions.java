package net.neoforged.neoform.runtime.engine;

import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/**
 * Customization options for Java compilation and related settings.
 */
public class BuildOptions {
    private final boolean useEclipseCompiler;

    @Unmodifiable
    @Nullable
    private final List<ClasspathItem> overriddenCompileClasspath;

    public BuildOptions(boolean useEclipseCompiler, @Nullable List<ClasspathItem> overriddenCompileClasspath) {
        this.useEclipseCompiler = useEclipseCompiler;
        this.overriddenCompileClasspath = overriddenCompileClasspath == null ? null : List.copyOf(overriddenCompileClasspath);
    }

    public boolean isUseEclipseCompiler() {
        return useEclipseCompiler;
    }

    @Unmodifiable
    public @Nullable List<ClasspathItem> getOverriddenCompileClasspath() {
        return overriddenCompileClasspath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuildOptions that = (BuildOptions) o;
        return useEclipseCompiler == that.useEclipseCompiler && Objects.equals(overriddenCompileClasspath, that.overriddenCompileClasspath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(useEclipseCompiler, overriddenCompileClasspath);
    }
}
