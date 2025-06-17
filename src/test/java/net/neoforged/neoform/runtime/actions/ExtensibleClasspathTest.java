package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.artifacts.ClasspathItem;
import net.neoforged.neoform.runtime.manifests.MinecraftDownload;
import net.neoforged.neoform.runtime.manifests.MinecraftLibrary;
import net.neoforged.neoform.runtime.utils.MavenCoordinate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtensibleClasspathTest {
    private static final MinecraftLibrary MC_LIB = new MinecraftLibrary(
            "group:artifact:1.2.3-mc", new MinecraftLibrary.Downloads(new MinecraftDownload("", 0, null, null), Map.of()), List.of(), null
    );
    private static final MavenCoordinate MAVEN_LIB = MavenCoordinate.parse("group:artifact:1.2.3-maven");
    private static final MavenCoordinate MAVEN_LIB_WITH_CLASSIFIER = MavenCoordinate.parse("group:artifact:1.2.3-maven:classifier");

    @Test
    void testMavenLibraryOverridesMinecraftLibrary() {
        var classpath = new ExtensibleClasspath();
        classpath.addMinecraftLibraries(List.of(MC_LIB));
        classpath.addMavenLibraries(List.of(MAVEN_LIB));

        assertThat(classpath.getEffectiveClasspath())
                .containsExactly(ClasspathItem.of(MAVEN_LIB));
    }

    @Test
    void testMinecraftLibraryOverridesMavenLibrary() {
        var classpath = new ExtensibleClasspath();
        classpath.addMavenLibraries(List.of(MAVEN_LIB));
        classpath.addMinecraftLibraries(List.of(MC_LIB));

        assertThat(classpath.getEffectiveClasspath())
                .containsExactly(ClasspathItem.of(MC_LIB));
    }

    @Test
    void testLibraryOverridesConsiderClassifiersAsDistinctive() {
        var classpath = new ExtensibleClasspath();
        classpath.addMavenLibraries(List.of(MAVEN_LIB));
        classpath.addMavenLibraries(List.of(MAVEN_LIB_WITH_CLASSIFIER));

        assertThat(classpath.getEffectiveClasspath())
                .containsExactly(ClasspathItem.of(MAVEN_LIB), ClasspathItem.of(MAVEN_LIB_WITH_CLASSIFIER));
    }
}
