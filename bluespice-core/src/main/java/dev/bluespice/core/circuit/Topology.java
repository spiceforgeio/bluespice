package dev.bluespice.core.circuit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Utility methods for connected-component analysis of circuit topology.
 */
public final class Topology {
    private Topology() {}

    /**
     * Returns node groups connected by non-ground component terminals.
     */
    public static List<Set<Node>> connectedComponents(Circuit circuit) {
        Partition partition = Partition.from(circuit);
        List<Set<Node>> components = new ArrayList<>();
        boolean groundIncluded = false;
        for (ComponentGroup group : partition.groups()) {
            Set<Node> nodes = new LinkedHashSet<>(group.nodes());
            if (!groundIncluded && group.touchesGround()) {
                nodes.add(partition.ground());
                groundIncluded = true;
            }
            components.add(Set.copyOf(nodes));
        }

        if (!groundIncluded) {
            components.add(Set.of(partition.ground()));
        }
        return List.copyOf(components);
    }

    /**
     * Returns whether the circuit contains multiple independent topology groups.
     */
    public static boolean isDisconnected(Circuit circuit) {
        return connectedComponents(circuit).size() > 1;
    }

    /**
     * Splits independent topology groups into separate circuit snapshots.
     */
    public static List<Circuit> split(Circuit circuit) {
        Partition partition = Partition.from(circuit);
        List<Circuit> parts = new ArrayList<>();
        int index = 1;
        for (ComponentGroup group : partition.groups()) {
            if (!group.components().isEmpty()) {
                parts.add(copyGroup(partition.snapshot(), group, index++));
            }
        }
        if (!partition.groundOnlyComponents().isEmpty() || parts.isEmpty()) {
            parts.add(copyGroundOnly(partition.snapshot(), partition.groundOnlyComponents(), index));
        }
        return List.copyOf(parts);
    }

    private static Circuit copyGroup(Circuit source, ComponentGroup group, int index) {
        Circuit copy = Circuit.empty(source.name() + "-part-" + index);
        Map<String, Node> nodesByLabel = copyNodes(copy, group.nodes());
        for (Component component : group.components()) {
            copyComponent(copy, nodesByLabel, component);
        }
        return copy;
    }

    private static Circuit copyGroundOnly(Circuit source, List<Component> components, int index) {
        Circuit copy = Circuit.empty(source.name() + "-part-" + index);
        Map<String, Node> nodesByLabel = new LinkedHashMap<>();
        nodesByLabel.put(copy.ground().label(), copy.ground());
        for (Component component : components) {
            copyComponent(copy, nodesByLabel, component);
        }
        return copy;
    }

    private static Map<String, Node> copyNodes(Circuit copy, Collection<Node> nodes) {
        Map<String, Node> nodesByLabel = new LinkedHashMap<>();
        nodesByLabel.put(copy.ground().label(), copy.ground());
        for (Node node : nodes) {
            if (!node.isGround()) {
                nodesByLabel.put(node.label(), copy.addNode(node.label()));
            }
        }
        return nodesByLabel;
    }

    private static void copyComponent(Circuit copy, Map<String, Node> nodesByLabel, Component component) {
        Node[] terminals = component.terminals().stream()
                .map(node -> node.isGround() ? copy.ground() : nodesByLabel.get(node.label()))
                .toArray(Node[]::new);
        copy.addComponent(component.type(), component.id(), component.value(), terminals);
    }

    private record ComponentGroup(
            Set<Node> nodes,
            List<Component> components,
            boolean touchesGround) {}

    private record Partition(
            Circuit snapshot,
            Node ground,
            List<ComponentGroup> groups,
            List<Component> groundOnlyComponents) {
        private static Partition from(Circuit circuit) {
            Objects.requireNonNull(circuit, "circuit");
            Circuit snapshot = circuit.snapshot();
            Node ground = snapshot.ground();
            Set<Node> nodes = snapshot.nodes();
            List<Component> graphComponents = List.copyOf(snapshot.components());

            Map<Node, Set<Node>> adjacency = buildAdjacency(nodes, graphComponents);
            List<Set<Node>> nodeGroups = bfs(adjacency);
            Map<Node, Integer> groupIndexes = groupIndexes(nodeGroups);
            List<List<Component>> groupedComponents = new ArrayList<>();
            List<Boolean> touchesGround = new ArrayList<>();
            for (int i = 0; i < nodeGroups.size(); i++) {
                groupedComponents.add(new ArrayList<>());
                touchesGround.add(false);
            }

            List<Component> groundOnlyComponents = new ArrayList<>();
            for (Component component : graphComponents) {
                List<Node> nonGround = nonGroundTerminals(component);
                if (nonGround.isEmpty()) {
                    groundOnlyComponents.add(component);
                    continue;
                }
                int groupIndex = groupIndexes.get(nonGround.get(0));
                groupedComponents.get(groupIndex).add(component);
                if (component.terminals().stream().anyMatch(Node::isGround)) {
                    touchesGround.set(groupIndex, true);
                }
            }

            List<ComponentGroup> groups = new ArrayList<>();
            for (int i = 0; i < nodeGroups.size(); i++) {
                groups.add(new ComponentGroup(
                        Set.copyOf(nodeGroups.get(i)),
                        List.copyOf(groupedComponents.get(i)),
                        touchesGround.get(i)));
            }
            return new Partition(
                    snapshot,
                    ground,
                    List.copyOf(groups),
                    List.copyOf(groundOnlyComponents));
        }

        private static Map<Node, Set<Node>> buildAdjacency(Set<Node> nodes, List<Component> components) {
            Map<Node, Set<Node>> adjacency = new LinkedHashMap<>();
            for (Node node : nodes) {
                if (!node.isGround()) {
                    adjacency.put(node, new LinkedHashSet<>());
                }
            }

            for (Component component : components) {
                List<Node> terminals = nonGroundTerminals(component);
                for (Node left : terminals) {
                    for (Node right : terminals) {
                        if (!left.equals(right)) {
                            adjacency.get(left).add(right);
                        }
                    }
                }
            }
            return adjacency;
        }

        private static List<Set<Node>> bfs(Map<Node, Set<Node>> adjacency) {
            List<Set<Node>> components = new ArrayList<>();
            Set<Node> visited = new LinkedHashSet<>();
            for (Node start : adjacency.keySet()) {
                if (visited.contains(start)) {
                    continue;
                }
                Set<Node> connected = new LinkedHashSet<>();
                Queue<Node> queue = new ArrayDeque<>();
                queue.add(start);
                visited.add(start);
                while (!queue.isEmpty()) {
                    Node current = queue.remove();
                    connected.add(current);
                    for (Node next : adjacency.get(current)) {
                        if (visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }
                components.add(Set.copyOf(connected));
            }
            return components;
        }

        private static Map<Node, Integer> groupIndexes(List<Set<Node>> nodeGroups) {
            Map<Node, Integer> indexes = new LinkedHashMap<>();
            for (int i = 0; i < nodeGroups.size(); i++) {
                for (Node node : nodeGroups.get(i)) {
                    indexes.put(node, i);
                }
            }
            return indexes;
        }

        private static List<Node> nonGroundTerminals(Component component) {
            return component.terminals().stream()
                    .filter(node -> !node.isGround())
                    .toList();
        }
    }
}
