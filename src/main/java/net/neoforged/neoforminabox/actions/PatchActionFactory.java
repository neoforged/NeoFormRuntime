package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.graph.ExecutionNodeBuilder;
import net.neoforged.neoforminabox.graph.NodeOutput;
import net.neoforged.neoforminabox.graph.NodeOutputType;
import net.neoforged.neoforminabox.utils.MavenCoordinate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PatchActionFactory {

    public static NodeOutput makeAction(ExecutionNodeBuilder builder,
                                        Path patchesArchive,
                                        String sourcePathInArchive,
                                        NodeOutput sources) {
        var patchesInZip = Objects.requireNonNull(sourcePathInArchive, "patches");
        builder.input("input", sources.asInput());
        var mainOutput = builder.output("output", NodeOutputType.ZIP, "ZIP file containing the patched sources");
        builder.output("outputRejects", NodeOutputType.ZIP, "ZIP file containing the rejected patches");

        var action = new ExternalJavaToolAction(MavenCoordinate.parse("codechicken:DiffPatch:1.5.0.29:all"));
        action.setArgs(List.of(
                "{input}", patchesArchive.toString(),
                "--prefix", patchesInZip,
                "--patch",
                "--archive", "ZIP",
                "--output", "{output}",
                "--log-level", "WARN",
                "--mode", "OFFSET",
                "--archive-rejects", "ZIP",
                "--reject", "{outputRejects}"
        ));

        builder.action(action);
        return mainOutput;
    }

}
