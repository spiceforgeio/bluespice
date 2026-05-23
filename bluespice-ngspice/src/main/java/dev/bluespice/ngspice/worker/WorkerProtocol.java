package dev.bluespice.ngspice.worker;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.bluespice.core.circuit.ComponentValue;
import dev.bluespice.core.sim.OperatingPointResult;
import dev.bluespice.core.sim.TransientConfig;
import dev.bluespice.core.sim.TransientResult;
import dev.bluespice.ngspice.CapturedIcState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkerProtocol {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .addMixIn(ComponentValue.class, ComponentValueMixin.class)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private WorkerProtocol() {}

    public static String serializeCommand(Command command) {
        return write(command);
    }

    public static Command deserializeCommand(String json) {
        return read(json, Command.class);
    }

    public static String serializeResponse(Response response) {
        return write(response);
    }

    public static Response deserializeResponse(String json) {
        return read(json, Response.class);
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize worker message", e);
        }
    }

    private static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to deserialize worker message", e);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Command.LoadCircuit.class, name = "LOAD_CIRCUIT"),
            @JsonSubTypes.Type(value = Command.RunOperatingPoint.class, name = "RUN_OP"),
            @JsonSubTypes.Type(value = Command.RunTransient.class, name = "RUN_TRAN"),
            @JsonSubTypes.Type(value = Command.Alter.class, name = "ALTER"),
            @JsonSubTypes.Type(value = Command.GetVector.class, name = "GET_VECTOR"),
            @JsonSubTypes.Type(value = Command.Reset.class, name = "RESET"),
            @JsonSubTypes.Type(value = Command.BgHalt.class, name = "BG_HALT"),
            @JsonSubTypes.Type(value = Command.Exit.class, name = "EXIT")
    })
    public sealed interface Command {
        record LoadCircuit(
                List<String> netlistLines,
                List<String> nodeNames,
                List<String> branchComponents
        ) implements Command {
            public LoadCircuit {
                netlistLines = List.copyOf(Objects.requireNonNull(netlistLines, "netlistLines"));
                nodeNames = List.copyOf(Objects.requireNonNull(nodeNames, "nodeNames"));
                branchComponents = List.copyOf(Objects.requireNonNull(branchComponents, "branchComponents"));
            }

            public LoadCircuit(String netlist) {
                this(netlist.lines().toList(), List.of(), List.of());
            }
        }

        record RunOperatingPoint() implements Command {}

        record RunTransient(TransientConfig config, boolean useInitialConditions) implements Command {
            public RunTransient(TransientConfig config) {
                this(config, false);
            }
        }

        record Alter(String componentId, ComponentValue newValue) implements Command {}

        record GetVector(String name) implements Command {}

        record Reset() implements Command {}

        record BgHalt() implements Command {}

        record Exit() implements Command {}
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Response.Ok.class, name = "OK"),
            @JsonSubTypes.Type(value = Response.Error.class, name = "ERROR"),
            @JsonSubTypes.Type(value = Response.ResultOp.class, name = "RESULT_OP"),
            @JsonSubTypes.Type(value = Response.ResultTran.class, name = "RESULT_TRAN"),
            @JsonSubTypes.Type(value = Response.Vector.class, name = "VECTOR")
    })
    public sealed interface Response {
        record Ok() implements Response {}

        record Error(String message) implements Response {}

        record ResultOp(OperatingPointResult result) implements Response {}

        record ResultTran(TransientResult result, CapturedIcState capturedIc) implements Response {
            public ResultTran(TransientResult result) {
                this(result, CapturedIcState.EMPTY);
            }
        }

        record Vector(String name, double[] values, Map<String, String> metadata) implements Response {}
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ComponentValue.Resistance.class, name = "Resistance"),
            @JsonSubTypes.Type(value = ComponentValue.Capacitance.class, name = "Capacitance"),
            @JsonSubTypes.Type(value = ComponentValue.Inductance.class, name = "Inductance"),
            @JsonSubTypes.Type(value = ComponentValue.DCVoltage.class, name = "DCVoltage"),
            @JsonSubTypes.Type(value = ComponentValue.DCCurrent.class, name = "DCCurrent"),
            @JsonSubTypes.Type(value = ComponentValue.ModelRef.class, name = "ModelRef"),
            @JsonSubTypes.Type(value = ComponentValue.SwitchState.class, name = "SwitchState"),
            @JsonSubTypes.Type(value = ComponentValue.PulseSource.class, name = "PulseSource")
    })
    private interface ComponentValueMixin {
    }
}
