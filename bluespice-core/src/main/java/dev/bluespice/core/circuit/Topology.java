package dev.bluespice.core.circuit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class Topology {
    private Topology() {}

    public static List<Set<Node>> connectedComponents(Circuit circuit) {
        Set<Node> nodes;
        List<Component> graphComponents;
        synchronized (circuit) {
            nodes = circuit.nodes();
            graphComponents = List.copyOf(circuit.components());
        }

        Map<Node, Set<Node>> adjacency = new LinkedHashMap<>();
        for (Node node : nodes) {
            adjacency.put(node, new LinkedHashSet<>());
        }

        for (Component component : graphComponents) {
            List<Node> terminals = component.terminals();
            for (Node left : terminals) {
                for (Node right : terminals) {
                    if (!left.equals(right)) {
                        adjacency.get(left).add(right);
                    }
                }
            }
        }

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
        return List.copyOf(components);
    }
}
