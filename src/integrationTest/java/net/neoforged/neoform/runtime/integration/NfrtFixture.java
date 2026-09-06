package net.neoforged.neoform.runtime.integration;

import java.io.IOException;
import java.nio.file.Path;

sealed interface NfrtFixture permits NeoFormFixture, NeoForgeFixture {
    Materialized materialize(Context context) throws IOException;

    record Context(Path testDirectory, String javaSourceTransformerCoordinate) {
    }

    record Materialized(Path launcherDirectory, String sourceArtifactArgument) {
    }
}
