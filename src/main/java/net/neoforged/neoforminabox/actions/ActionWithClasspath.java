package net.neoforged.neoforminabox.actions;

import net.neoforged.neoforminabox.artifacts.ClasspathItem;
import net.neoforged.neoforminabox.graph.ExecutionNodeAction;

import java.util.List;

public interface ActionWithClasspath extends ExecutionNodeAction {
    List<ClasspathItem> getClasspath();
}
