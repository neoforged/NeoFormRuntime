package net.neoforged.neoform.runtime.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;

import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR_NO_RECOMP;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_SOURCES;
import static net.neoforged.neoform.runtime.integration.ClassFileAssertions.assertImplementsInterface;
import static org.assertj.core.api.Assertions.assertThat;

class SourceTransformIntegrationTest {
    @ParameterizedTest(name = "absolute paths: {0}")
    @ValueSource(booleans = {false, true})
    void appliesAccessTransformerPaths(boolean absolutePaths, @TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .source("Example.java", """
                        class Example {
                            private int regularField;
                            private int validatedField;
                        }
                        """)
                .build();

        var regularAtPath = Path.of("config/regular-access-transformer.cfg");
        var validatedAtPath = Path.of("config/validated-access-transformer.cfg");
        if (absolutePaths) {
            regularAtPath = tempDir.resolve(regularAtPath).toAbsolutePath();
            validatedAtPath = tempDir.resolve(validatedAtPath).toAbsolutePath();
        }

        var command = NfrtCommand.builder(tempDir, fixture)
                .accessTransformer(
                        regularAtPath,
                        "public Example regularField\n"
                )
                .validatedAccessTransformer(
                        validatedAtPath,
                        "public Example validatedField\n"
                )
                .result(GAME_SOURCES)
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES, "Example.java"))
                .isEqualTo("""
                        class Example {
                            public int regularField;
                            public int validatedField;
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR)).contains("Example.class");
    }

    @Test
    void appliesModernTransformsToSourcesAndClasses(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .minecraftVersion("1.21")
                .source("example/Example.java", """
                        package example;
                        public class Example {
                            private int regularField;
                            private int validatedField;

                            static class Nested {
                            }
                        }
                        """)
                .compileLauncherSources()
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .accessTransformer("""
                        public example.Example regularField
                        public example.Example$Nested
                        """)
                .validatedAccessTransformer("public example.Example validatedField\n")
                .parchmentData(ParchmentData.builder()
                        .classJavadoc("example/Example", "Documented by Parchment")
                        .build())
                .result(GAME_SOURCES)
                .result(GAME_JAR)
                .result(GAME_JAR_NO_RECOMP)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES, "example/Example.java"))
                .isEqualTo("""
                        package example;
                        /**
                         * Documented by Parchment
                         */
                        public class Example {
                            public int regularField;
                            public int validatedField;

                            public static class Nested {
                                Nested() {}
                            }
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR))
                .contains(
                        "example/Example.class",
                        "example/Example$Nested.class"
                );
        try (var loader = new URLClassLoader(
                new URL[]{command.resultPath(GAME_JAR_NO_RECOMP).toUri().toURL()}
        )) {
            var exampleClass = loader.loadClass("example.Example");
            assertThat(Modifier.isPublic(exampleClass.getDeclaredField("regularField").getModifiers())).isTrue();
            assertThat(Modifier.isPublic(exampleClass.getDeclaredField("validatedField").getModifiers())).isTrue();
        }
    }

    @Test
    void recompilesConsumersAgainstTransformedSources(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .source("Example.java", """
                        class Example {
                            private int value;
                        }
                        """)
                .source("Consumer.java", """
                        class Consumer {
                            int read(Example value) {
                                return value.value;
                            }
                        }
                        """)
                .build();
        var untransformedCommand = NfrtCommand.builder(tempDir.resolve("untransformed"), fixture)
                .result(GAME_JAR)
                .build();
        assertThat(untransformedCommand.executeExpectingFailure())
                .contains("value has private access in Example");

        var command = NfrtCommand.builder(tempDir.resolve("transformed"), fixture)
                .accessTransformer("public Example value\n")
                .result(GAME_SOURCES)
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES, "Example.java"))
                .isEqualTo("""
                        class Example {
                            public int value;
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR))
                .contains("Example.class", "Consumer.class");
    }

    @Test
    void recompilesConsumersOfTransformedCompileTimeConstants(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .source("example/Constants.java", """
                        package example;

                        public class Constants {
                            public static final int VALUE = 1;
                        }

                        class Secondary {
                        }
                        """)
                .source("example/Consumer.java", """
                        package example;

                        public class Consumer {
                            public static int value() {
                                return Constants.VALUE;
                            }
                        }
                        """)
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .validatedAccessTransformer("public-f example.Constants VALUE\n")
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();

        assertThat(command.resultEntries(GAME_JAR))
                .contains("example/Constants.class", "example/Secondary.class", "example/Consumer.class");
        try (var loader = new URLClassLoader(new URL[]{command.resultPath(GAME_JAR).toUri().toURL()})) {
            var constants = loader.loadClass("example.Constants");
            constants.getField("VALUE").setInt(null, 7);
            assertThat(loader.loadClass("example.Consumer").getMethod("value").invoke(null)).isEqualTo(7);
        }
    }

    @Test
    void compilesAgainstGeneratedInterfaceStubsAndTransformsBinaryResult(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .minecraftVersion("1.21")
                .source("example/Target.java", """
                        package example;

                        public class Target {
                        }
                        """)
                .compileLauncherSources()
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .interfaceInjectionData(Map.of("example/Target", "injected/MissingInterface"))
                .result(GAME_SOURCES)
                .result(GAME_JAR)
                .result(GAME_JAR_NO_RECOMP)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES, "example/Target.java"))
                .isEqualTo("""
                        package example;

                        import injected.MissingInterface;

                        public class Target implements MissingInterface {
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR))
                .contains("example/Target.class")
                .doesNotContain("injected/MissingInterface.class");
        assertImplementsInterface(
                command.readResultBytes(GAME_JAR, "example/Target.class"),
                "injected/MissingInterface"
        );
        assertImplementsInterface(
                command.readResultBytes(GAME_JAR_NO_RECOMP, "example/Target.class"),
                "injected/MissingInterface"
        );
    }

    @Test
    void appliesLegacyTransformsInNamingOrder(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .minecraftVersion("1.20.1")
                .source("Example.java", """
                        class Example {
                            private int f_1_;
                        }
                        """)
                .legacyMappings(LegacyMappings.builder()
                        .classMapping("Example", "a")
                        .fieldMapping("int", "officialField", "b", "f_1_")
                        .build())
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .accessTransformer("public Example f_1_\n")
                .parchmentData(ParchmentData.builder()
                        .fieldJavadoc("Example", "officialField", "I", "Mapped field documentation")
                        .build())
                .result(GAME_SOURCES)
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult(GAME_SOURCES, "Example.java"))
                .isEqualTo("""
                        class Example {
                            /**
                             * Mapped field documentation
                             */
                            public int officialField;
                        }
                        """);
        assertThat(command.resultEntries(GAME_JAR)).contains("Example.class");
    }
}
