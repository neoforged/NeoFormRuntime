package net.neoforged.neoform.runtime.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR;
import static net.neoforged.neoform.runtime.cli.ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE;
import static org.assertj.core.api.Assertions.assertThat;

class CacheIntegrationTest {
    @Test
    void reusesCachedWorkAcrossInvocations(@TempDir Path tempDir) throws Exception {
        var fixture = NeoFormFixture.builder()
                .source("Example.java", "class Example {}")
                .build();
        var command = NfrtCommand.builder(tempDir, fixture)
                .enableCache()
                .result(GAME_JAR)
                .build();

        command.executeSuccessfully();
        var secondOutput = command.executeSuccessfully();

        assertThat(secondOutput)
                .contains("REUSE Used cache of decompile")
                .contains("REUSE Used cache of recompile");
        assertThat(command.resultEntries(GAME_JAR)).contains("Example.class");
    }

    @Test
    void invalidatesBinaryPatchOutputWhenPatchDataChanges(@TempDir Path tempDir) throws Exception {
        var firstCommand = binaryPatchCommand(tempDir, "first binary patch");
        firstCommand.executeSuccessfully();

        assertThat(firstCommand.executeSuccessfully()).contains("REUSE Used cache of binaryPatch");

        var changedCommand = binaryPatchCommand(tempDir, "changed binary patch");
        assertThat(changedCommand.executeSuccessfully())
                .contains("*** Started working on binaryPatch")
                .doesNotContain("REUSE Used cache of binaryPatch");
    }

    private static NfrtCommand binaryPatchCommand(Path tempDir, String binaryPatch) throws Exception {
        var neoForm = NeoFormFixture.builder()
                .source("example/Example.java", "package example; public class Example {}")
                .compileLauncherSources()
                .build();
        var fixture = NeoForgeFixture.builder(neoForm)
                .binaryPatch(binaryPatch.getBytes(StandardCharsets.UTF_8))
                .build();
        return NfrtCommand.builder(tempDir, fixture)
                .enableCache()
                .result(GAME_JAR_NO_RECOMP_WITH_NEOFORGE)
                .build();
    }
}
