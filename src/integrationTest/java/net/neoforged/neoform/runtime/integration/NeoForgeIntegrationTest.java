package net.neoforged.neoform.runtime.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR_WITH_NEOFORGE;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR_WITH_SOURCES_AND_NEOFORGE;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_SOURCES_WITH_NEOFORGE;
import static org.assertj.core.api.Assertions.assertThat;

class NeoForgeIntegrationTest {
    @Test
    void routesTransformedSourcesAndCompiledClassesToNeoForgeResults(@TempDir Path tempDir) throws Exception {
        var neoForm = NeoFormFixture.builder()
                .minecraftVersion("1.21")
                .source("example/Game.java", """
                        package example;

                        public class Game {
                            private int neoForgeTransformed;
                            private int userTransformed;
                        }
                        """)
                .compileLauncherSources()
                .build();
        var fixture = NeoForgeFixture.builder(neoForm)
                .source("neoforge/Added.java", "package neoforge; public class Added {}")
                .universalEntry("neoforge-marker.txt", "universal")
                .accessTransformer("public example.Game neoForgeTransformed\n")
                .patch("example/Game.java.patch", """
                        --- a/example/Game.java
                        +++ b/example/Game.java
                        @@ -3,4 +3,8 @@
                         public class Game {
                             private int neoForgeTransformed;
                             private int userTransformed;
                        +
                        +    public String patched() {
                        +        return "yes";
                        +    }
                         }
                        """)
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .accessTransformer("public example.Game userTransformed\n")
                .result(GAME_JAR)
                .result(GAME_SOURCES_WITH_NEOFORGE)
                .result(GAME_JAR_WITH_NEOFORGE)
                .result(GAME_JAR_WITH_SOURCES_AND_NEOFORGE)
                .result(GAME_JAR_NO_RECOMP_WITH_NEOFORGE)
                .build();

        command.executeSuccessfully();

        assertThat(command.resultEntries(GAME_JAR))
                .contains("example/Game.class")
                .doesNotContain("neoforge/Added.java", "neoforge-marker.txt");
        assertThat(command.resultEntries(GAME_SOURCES_WITH_NEOFORGE))
                .contains("example/Game.java", "neoforge/Added.java")
                .doesNotContain("example/Game.class", "neoforge-marker.txt");
        assertThat(command.readResult(GAME_SOURCES_WITH_NEOFORGE, "example/Game.java"))
                .isEqualTo("""
                        package example;

                        public class Game {
                            public int neoForgeTransformed;
                            public int userTransformed;

                            public String patched() {
                                return "yes";
                            }
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR_WITH_NEOFORGE))
                .contains("example/Game.class", "neoforge-marker.txt")
                .doesNotContain("example/Game.java", "neoforge/Added.java");
        assertThat(command.resultEntries(GAME_JAR_WITH_SOURCES_AND_NEOFORGE))
                .contains("example/Game.class", "example/Game.java", "neoforge/Added.java", "neoforge-marker.txt");
        assertThat(command.resultEntries(GAME_JAR_NO_RECOMP_WITH_NEOFORGE))
                .contains("example/Game.class", "neoforge-marker.txt")
                .doesNotContain("example/Game.java");
        try (var loader = new URLClassLoader(
                new URL[]{command.resultPath(GAME_JAR_NO_RECOMP_WITH_NEOFORGE).toUri().toURL()}
        )) {
            var gameClass = loader.loadClass("example.Game");
            assertThat(Modifier.isPublic(gameClass.getDeclaredField("neoForgeTransformed").getModifiers())).isTrue();
            assertThat(Modifier.isPublic(gameClass.getDeclaredField("userTransformed").getModifiers())).isTrue();
        }
    }

    @Test
    void appliesSourcePatchesWithConfiguredPrefixes(@TempDir Path tempDir) throws Exception {
        var neoForm = NeoFormFixture.builder()
                .source("example/Example.java", """
                        package example;

                        public class Example {
                            private int first;
                            private int second;
                        }
                        """)
                .build();
        var fixture = NeoForgeFixture.builder(neoForm)
                .patchPrefixes("original/", "modified/")
                .patch("example/Example.java.patch", """
                        --- original/example/Example.java
                        +++ modified/example/Example.java
                        @@ -3,4 +3,8 @@
                         public class Example {
                             private int first;
                             private int second;
                        +    public String patched() {
                        +        return "yes";
                        +    }
                         }
                        """)
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .result(GAME_SOURCES_WITH_NEOFORGE)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES_WITH_NEOFORGE, "example/Example.java"))
                .isEqualTo("""
                        package example;

                        public class Example {
                            private int first;
                            private int second;
                            public String patched() {
                                return "yes";
                            }
                        }
                        """);
    }

    @Test
    void appliesConfiguredLegacySideAnnotationStrippers(@TempDir Path tempDir) throws Exception {
        var neoForm = NeoFormFixture.builder()
                .minecraftVersion("1.20.1")
                .source("Example.java", """
                        @Deprecated
                        class Example {
                        }
                        """)
                .launcherSource("a.java", """
                        @Deprecated
                        class a {
                        }
                        """)
                .legacyMappings(LegacyMappings.builder()
                        .classMapping("Example", "a")
                        .build())
                .build();
        var fixture = NeoForgeFixture.builder(neoForm)
                .sideAnnotationStripper("a\n")
                .patch("Example.java.patch", """
                        --- a/Example.java
                        +++ b/Example.java
                        @@ -1,3 +1,4 @@
                         @Deprecated
                         class Example {
                        +    int value;
                         }
                        """)
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .result(GAME_SOURCES_WITH_NEOFORGE)
                .build();

        var output = command.executeSuccessfully();

        assertThat(output)
                .contains("*** Started working on stripSideAnnotations");
        assertThat(command.readResult(GAME_SOURCES_WITH_NEOFORGE, "Example.java"))
                .isEqualTo("""
                        @Deprecated
                        class Example {
                            int value;
                        }
                        """);
    }

    @Test
    void addsNeoForgeLibrariesAndUniversalJarToTheCompileClasspath(@TempDir Path tempDir) throws Exception {
        var neoForgeLibrary = NfrtFixtureSupport.compileSource(
                tempDir.resolve("neoforge-library"),
                "neoforge/library/LibraryDependency.java",
                """
                        package neoforge.library;

                        public class LibraryDependency {
                        }
                        """,
                21
        );
        var universalJar = NfrtFixtureSupport.compileSource(
                tempDir.resolve("neoforge-universal"),
                "neoforge/universal/UniversalDependency.java",
                """
                        package neoforge.universal;

                        public class UniversalDependency {
                        }
                        """,
                21
        );
        var neoForm = NeoFormFixture.builder()
                .source("example/UsesNeoForgeClasspath.java", """
                        package example;

                        import neoforge.library.LibraryDependency;
                        import neoforge.universal.UniversalDependency;

                        public class UsesNeoForgeClasspath {
                            private LibraryDependency libraryDependency;
                            private UniversalDependency universalDependency;
                        }
                        """)
                .build();
        var fixture = NeoForgeFixture.builder(neoForm)
                .library("fixture:neoforge-library:1")
                .universalEntry(
                        "neoforge/universal/UniversalDependency.class",
                        NfrtFixtureSupport.readZipEntry(
                                universalJar,
                                "neoforge/universal/UniversalDependency.class"
                        )
                )
                .patch("example/UsesNeoForgeClasspath.java.patch", """
                        --- a/example/UsesNeoForgeClasspath.java
                        +++ b/example/UsesNeoForgeClasspath.java
                        @@ -6,4 +6,4 @@
                         public class UsesNeoForgeClasspath {
                        -    private LibraryDependency libraryDependency;
                        +    public LibraryDependency libraryDependency;
                             private UniversalDependency universalDependency;
                         }
                        """)
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .artifact("fixture:neoforge-library:1", neoForgeLibrary)
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();

        assertThat(command.resultEntries(GAME_JAR)).contains("example/UsesNeoForgeClasspath.class");
    }
}
