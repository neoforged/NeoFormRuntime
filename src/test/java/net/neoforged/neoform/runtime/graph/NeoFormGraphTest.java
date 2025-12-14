package net.neoforged.neoform.runtime.graph;

import net.neoforged.neoform.runtime.cli.Main;
import net.neoforged.neoform.runtime.cli.ResultIds;
import net.neoforged.neoform.runtime.cli.RunNeoFormCommand;
import net.neoforged.neoform.runtime.engine.NeoFormEngine;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression tests for the structure of the graph when building NeoForm.
 */
public class NeoFormGraphTest {
    @Test
    void testNeoForm_1_21() throws Exception {
        var graph = buildGraph("--neoform", "net.neoforged:neoform:1.21-20240613.152323@zip");
        assertThat(graph.getNodes()).extracting("id").doesNotContain("applyDevTransforms");

        // No Recompile Pipeline
        assertResultFromNode(graph, "rename", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    @Test
    void testNeoForm_1_21_WithDevTransforms() throws Exception {
        var graph = buildGraph("--neoform", "net.neoforged:neoform:1.21-20240613.152323@zip", "--access-transformer", "at.cfg");
        assertNotPredecessor(graph, "applyDevTransforms", "decompile");

        // No Recompile Pipeline
        assertNodeChain(graph, "rename", "applyDevTransforms");
        assertResultFromNode(graph, "applyDevTransforms", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    @Test
    void testNeoForge_1_21() throws Exception {
        var graph = buildGraph("--neoforge", "net.neoforged:neoforge:21.0.0-beta:userdev");

        // No Recompile Pipeline
        assertNodeChain(graph, "rename", "binaryPatch", "copyUnpatchedClasses", "applyDevTransforms", "binaryWithNeoForge");
        assertResultFromNode(graph, "applyDevTransforms", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "binaryWithNeoForge", "output", ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    @Test
    void testMCP_1_20_1() throws Exception {
        var graph = buildGraph("--neoform", "de.oceanlabs.mcp:mcp_config:1.20.1@zip");
        assertThat(graph.getNodes()).extracting("id").doesNotContain("applyDevTransforms");

        // No Recompile Pipeline
        assertNodeChain(graph, "rename", "remapSrgClassesToOfficial");
        assertResultFromNode(graph, "remapSrgClassesToOfficial", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    @Test
    void testMCP_1_20_1_WithDevTransforms() throws Exception {
        var graph = buildGraph("--neoform", "de.oceanlabs.mcp:mcp_config:1.20.1@zip", "--access-transformer", "at.cfg");
        assertNotPredecessor(graph, "applyDevTransforms", "decompile");

        // No Recompile Pipeline
        assertNodeChain(graph, "rename", "applyDevTransforms", "remapSrgClassesToOfficial");
        assertResultFromNode(graph, "remapSrgClassesToOfficial", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    @Test
    void testNeoForge_1_20_1() throws Exception {
        var graph = buildGraph("--neoforge", "net.neoforged:forge:1.20.1-47.1.54:userdev");

        // No Recompile Pipeline
        assertNodeChain(graph, "rename", "binaryPatch", "copyUnpatchedClasses", "applyDevTransforms", "binaryWithNeoForge", "remapSrgClassesToOfficial");
        assertResultFromNode(graph, "remapSrgClassesToOfficial", "output", ResultIds.GAME_JAR_NO_RECOMP);
        assertResultFromNode(graph, "remapSrgClassesToOfficial", "output", ResultIds.GAME_JAR_NO_RECOMP_WITH_NEOFORGE);
        assertResultFromNode(graph, "rename", "output", ResultIds.VANILLA_DEOBFUSCATED);
    }

    private static void assertResultFromNode(ExecutionGraph graph, String nodeId, String outputId, String resultId) {
        var output = graph.getResult(resultId);
        assertEquals(nodeId, output.getNode().id(), "Expected result " + resultId + " to be from node " + nodeId);
        assertEquals(outputId, output.id(), "Expected result of " + nodeId + " to be from output " + outputId);
    }

    /**
     * Asserts that the given nodes form a succession in the graph.
     */
    private static void assertNodeChain(ExecutionGraph graph, String... chainedNodeIds) {
        for (int i = chainedNodeIds.length - 1; i > 0; i--) {
            var nodeId = chainedNodeIds[i];
            assertPredecessor(graph, chainedNodeIds[i - 1], nodeId);
        }
        if (chainedNodeIds.length > 0) {
            graph.getRequiredNode(chainedNodeIds[0]);
        }
    }

    private static Set<ExecutionNode> getPredecessors(ExecutionGraph graph, String nodeId) {
        Set<ExecutionNode> result = Collections.newSetFromMap(new IdentityHashMap<>());
        List<ExecutionNode> openSet = new ArrayList<>();
        openSet.add(graph.getRequiredNode(nodeId));
        while (!openSet.isEmpty()) {
            var node = openSet.removeLast();
            for (var entry : node.inputs().entrySet()) {
                for (var nodeDependency : entry.getValue().getNodeDependencies()) {
                    if (result.add(nodeDependency)) {
                        openSet.add(nodeDependency);
                    }
                }
            }
        }
        return result;
    }

    private static void assertPredecessor(ExecutionGraph graph, String nodeId, String otherNodeId) {
        assertThat(getPredecessors(graph, otherNodeId))
                .as("Expected %s to be in the set of transitive predecessor nodes of %s", nodeId, otherNodeId)
                .extracting("id").contains(nodeId);
    }

    private static void assertNotPredecessor(ExecutionGraph graph, String nodeId, String otherNodeId) {
        assertNotPredecessor(graph, nodeId, otherNodeId, new LinkedHashSet<>());
    }

    private static void assertNotPredecessor(ExecutionGraph graph, String nodeId, String otherNodeId, Set<String> visitedNodes) {
        if (visitedNodes.contains(otherNodeId)) {
            fail("Cycle in graph: " + String.join(" -> ", visitedNodes) + " -> " + otherNodeId);
        }

        var node1 = graph.getRequiredNode(nodeId);
        var node2 = graph.getRequiredNode(otherNodeId);

        visitedNodes.add(node2.id());
        for (var entry : node2.inputs().entrySet()) {
            for (ExecutionNode nodeDependency : entry.getValue().getNodeDependencies()) {
                if (nodeDependency == node1) {
                    fail(node1 + " is predecessor of " + node2 + " along chain " + String.join(" -> ", visitedNodes));
                }
                assertNotPredecessor(graph, nodeId, nodeDependency.id(), visitedNodes);
            }
        }
        visitedNodes.remove(node2.id());
    }

    private static ExecutionGraph buildGraph(String... args) throws Exception {
        var fullArgs = new ArrayList<String>();
        Collections.addAll(fullArgs, "run", "--print-graph");
        Collections.addAll(fullArgs, args);

        var graphHolder = new AtomicReference<ExecutionGraph>();
        var command = new RunNeoFormCommand() {
            @Override
            protected void runWithNeoFormEngine(NeoFormEngine engine, List<AutoCloseable> closables) throws IOException, InterruptedException {
                super.runWithNeoFormEngine(engine, closables);
                graphHolder.set(engine.getGraph()); // Capture the graph.
            }
        };
        var defaultFactory = CommandLine.defaultFactory();
        var commandLine = new CommandLine(new Main(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> cls) throws Exception {
                if (RunNeoFormCommand.class.isAssignableFrom(cls)) {
                    return cls.cast(command);
                }
                return defaultFactory.create(cls);
            }
        });
        commandLine.parseArgs(fullArgs.toArray(String[]::new));
        assertEquals(0, commandLine.execute(fullArgs.toArray(String[]::new)));

        var graph = graphHolder.get();
        assertNotNull(graph);
        return graph;
    }

}
