package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class PatchActionFactory {

    public static NodeOutput makeAction(ExecutionNodeBuilder builder,
                                        Path patchesArchive,
                                        String sourcePathInArchive,
                                        NodeOutput sources,
                                        String stripBasePrefix,
                                        String stripModifiedPrefix) {
        var patchesInZip = Objects.requireNonNull(sourcePathInArchive, "patches");
        builder.input("input", sources.asInput());
        var mainOutput = builder.output("output", NodeOutputType.ZIP, "ZIP file containing the patched sources");
        builder.output("outputRejects", NodeOutputType.ZIP, "ZIP file containing the rejected patches");

        var action = new ExternalJavaToolAction(ToolCoordinate.DIFF_PATCH);
        action.setArgs(List.of(
                "{input}", patchesArchive.toString(),
                "--prefix", patchesInZip,
                "--patch",
                "--archive", "ZIP",
                "--output", "{output}",
                "--log-level", "WARN",
                "--mode", "OFFSET",
                "--archive-rejects", "ZIP",
                "--reject", "{outputRejects}",
                "--strip-base-prefix", stripBasePrefix,
                "--strip-modified-prefix", stripModifiedPrefix
        ));

        builder.action(action);
        return mainOutput;
    }

}
