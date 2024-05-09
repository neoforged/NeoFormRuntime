package net.neoforged.neoforminabox.engine;

import net.neoforged.neoforminabox.artifacts.ClasspathItem;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Customization options for Java compilation and related settings.
 */
public class BuildOptions {
    private boolean useEclipseCompiler;

    @Nullable
    private List<ClasspathItem> overriddenCompileClasspath;

    public boolean isUseEclipseCompiler() {
        return useEclipseCompiler;
    }

    public void setUseEclipseCompiler(boolean useEclipseCompiler) {
        this.useEclipseCompiler = useEclipseCompiler;
    }

    public @Nullable List<ClasspathItem> getOverriddenCompileClasspath() {
        return overriddenCompileClasspath;
    }

    public void setOverriddenCompileClasspath(@Nullable List<ClasspathItem> overriddenCompileClasspath) {
        if (overriddenCompileClasspath != null) {
            this.overriddenCompileClasspath = List.copyOf(overriddenCompileClasspath);
        } else {
            this.overriddenCompileClasspath = null;
        }
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
