package net.neoforged.neoform.runtime.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunNeoFormCommandTest {
    private static final String EXAMPLE_SOURCE = """
            class Example {
                private int regularField;
                private int validatedField;
            }
            """;
    private static final String REGULAR_AT = "public Example regularField\n";
    private static final String VALIDATED_AT = "public Example validatedField\n";

    @Test
    void appliesRelativeAccessTransformerPaths(@TempDir Path tempDir) throws Exception {
        var command = NfrtCommand.builder(tempDir)
                .source("Example.java", EXAMPLE_SOURCE)
                .accessTransformer("config/regular-access-transformer.cfg", REGULAR_AT)
                .validatedAccessTransformer("config/validated-access-transformer.cfg", VALIDATED_AT)
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult("gameSources", "Example.java"))
                .contains("public int regularField;")
                .contains("public int validatedField;");
    }

    @Test
    void appliesAbsoluteAccessTransformerPaths(@TempDir Path tempDir) throws Exception {
        var command = NfrtCommand.builder(tempDir)
                .source("Example.java", EXAMPLE_SOURCE)
                .accessTransformer(
                        tempDir.resolve("config/regular-access-transformer.cfg").toAbsolutePath(),
                        REGULAR_AT
                )
                .validatedAccessTransformer(
                        tempDir.resolve("config/validated-access-transformer.cfg").toAbsolutePath(),
                        VALIDATED_AT
                )
                .build();

        command.executeSuccessfully();

        assertThat(command.readResult("gameSources", "Example.java"))
                .contains("public int regularField;")
                .contains("public int validatedField;");
    }
}
