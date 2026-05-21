package dev.bluespice.core.circuit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

import static dev.bluespice.core.circuit.CircuitValidation.requireText;

public final class Circuit {
    private final String name;
    private final Map<String, Node> nodesByLabel;
    private final Map<String, Component> componentsById;
    private long nextNodeId;

    private Circuit(String name) {
        this.name = requireText(name, "name");
        this.nodesByLabel = new LinkedHashMap<>();
        this.componentsById = new LinkedHashMap<>();
        this.nextNodeId = 1;
        nodesByLabel.put("0", new Node("0", 0, true));
    }

    private Circuit(String name, long nextNodeId, Map<String, Node> nodesByLabel,
                    Map<String, Component> componentsById) {
        this.name = name;
        this.nextNodeId = nextNodeId;
        this.nodesByLabel = nodesByLabel;
        this.componentsById = componentsById;
    }

    public static Circuit empty(String name) {
        return new Circuit(name);
    }

    public synchronized Node addNode(String label) {
        String normalized = normalizeLabel(label);
        if (isGroundLabel(normalized)) {
            return ground();
        }
        if (nodesByLabel.containsKey(normalized)) {
            throw new IllegalArgumentException("node already exists: " + normalized);
        }
        Node node = new Node(normalized, nextNodeId++, false);
        nodesByLabel.put(normalized, node);
        return node;
    }

    public synchronized Node ground() {
        return nodesByLabel.get("0");
    }

    public synchronized Node getNode(String label) {
        String normalized = normalizeLabel(label);
        Node node = nodesByLabel.get(isGroundLabel(normalized) ? "0" : normalized);
        if (node == null) {
            throw new NoSuchElementException("node not found: " + normalized);
        }
        return node;
    }

    public synchronized void removeNode(Node node) {
        Objects.requireNonNull(node, "node");
        if (node.isGround()) {
            throw new IllegalArgumentException("ground node cannot be removed");
        }
        Node stored = nodesByLabel.get(node.label());
        if (node != stored) {
            throw new NoSuchElementException("node not found: " + node.label());
        }
        nodesByLabel.remove(node.label());
        componentsById.values().removeIf(component -> component.terminals().contains(node));
    }

    public Component addComponent(ComponentType type, String id, ComponentValue value, Node positive, Node negative) {
        return addComponent(type, id, value, new Node[] {positive, negative});
    }

    public synchronized Component addComponent(ComponentType type, String id, ComponentValue value, Node... terminals) {
        String normalizedId = requireText(id, "id");
        if (componentsById.containsKey(normalizedId)) {
            throw new IllegalArgumentException("component already exists: " + normalizedId);
        }
        List<Node> validated = validateTerminals(terminals);
        Component component = new Component(normalizedId, type, value, validated);
        componentsById.put(normalizedId, component);
        return component;
    }

    public synchronized void removeComponent(String id) {
        String normalizedId = requireText(id, "id");
        if (componentsById.remove(normalizedId) == null) {
            throw new NoSuchElementException("component not found: " + normalizedId);
        }
    }

    public synchronized void updateValue(String id, ComponentValue newValue) {
        String normalizedId = requireText(id, "id");
        Component component = getComponent(normalizedId);
        componentsById.put(normalizedId, component.withValue(newValue));
    }

    public synchronized Set<Node> nodes() {
        return new LinkedHashSet<>(nodesByLabel.values());
    }

    public synchronized Collection<Component> components() {
        return List.copyOf(componentsById.values());
    }

    public synchronized Component getComponent(String id) {
        String normalizedId = requireText(id, "id");
        Component component = componentsById.get(normalizedId);
        if (component == null) {
            throw new NoSuchElementException("component not found: " + normalizedId);
        }
        return component;
    }

    public String name() {
        return name;
    }

    public synchronized Circuit snapshot() {
        Map<String, Node> copiedNodes = new LinkedHashMap<>();
        Map<Long, Node> byInternalId = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : nodesByLabel.entrySet()) {
            Node node = entry.getValue();
            Node copy = new Node(node.label(), node.internalId(), node.isGround());
            copiedNodes.put(entry.getKey(), copy);
            byInternalId.put(copy.internalId(), copy);
        }

        Map<String, Component> copiedComponents = new LinkedHashMap<>();
        for (Component component : componentsById.values()) {
            List<Node> terminals = component.terminals().stream()
                    .map(node -> byInternalId.get(node.internalId()))
                    .toList();
            copiedComponents.put(component.id(), new Component(
                    component.id(), component.type(), component.value(), terminals));
        }
        return new Circuit(name, nextNodeId, copiedNodes, copiedComponents);
    }

    private List<Node> validateTerminals(Node[] terminals) {
        Objects.requireNonNull(terminals, "terminals");
        List<Node> validated = new ArrayList<>(terminals.length);
        for (Node terminal : terminals) {
            Objects.requireNonNull(terminal, "terminal");
            Node stored = terminal.isGround() ? nodesByLabel.get("0") : nodesByLabel.get(terminal.label());
            if (terminal != stored) {
                throw new IllegalArgumentException("terminal does not belong to this circuit: " + terminal);
            }
            validated.add(stored);
        }
        return validated;
    }

    private static String normalizeLabel(String label) {
        return requireText(label, "label");
    }

    private static boolean isGroundLabel(String label) {
        return "0".equals(label) || "gnd".equalsIgnoreCase(label);
    }

}
