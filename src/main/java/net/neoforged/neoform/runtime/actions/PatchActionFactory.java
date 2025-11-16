package net.neoforged.neoform.runtime.actions;

import net.neoforged.neoform.runtime.engine.DataSource;
import net.neoforged.neoform.runtime.graph.ExecutionNodeBuilder;
import net.neoforged.neoform.runtime.graph.NodeOutput;
import net.neoforged.neoform.runtime.graph.NodeOutputType;
import net.neoforged.neoform.runtime.utils.ToolCoordinate;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

public final class PatchActionFactory {

    public static NodeOutput makeAction(ExecutionNodeBuilder builder,
                                        DataSource patches,
                                        @Nullable NodeOutput sources,
                                        String basePathPrefix,
                                        String modifiedPathPrefix) {
        Objects.requireNonNull(patches, "patches");
        if (sources != null ) {
            builder.input("input", sources.asInput());
        } else if (!builder.hasInput("input")) {
            throw new IllegalStateException("The builder must have an input named 'input' when no explit sources are given.");
        }
        var mainOutput = builder.output("output", NodeOutputType.ZIP, "ZIP file containing the patched sources");
        builder.output("outputRejects", NodeOutputType.ZIP, "ZIP file containing the rejected patches");

        var action = new ExternalJavaToolAction(ToolCoordinate.DIFF_PATCH);
        action.setArgs(List.of(
                "{input}", patches.archive().getName(),
                "--prefix", patches.folder(),
                "--patch",
                "--archive", "ZIP",
                "--output", "{output}",
                "--log-level", "WARN",
                "--mode", "OFFSET",
                "--archive-rejects", "ZIP",
                "--reject", "{outputRejects}",
                "--base-path-prefix", basePathPrefix,
                "--modified-path-prefix", modifiedPathPrefix
        ));
        action.addDataDependencyHash("patches", patches::cacheKey);

        builder.action(action);
        return mainOutput;
    }

}
