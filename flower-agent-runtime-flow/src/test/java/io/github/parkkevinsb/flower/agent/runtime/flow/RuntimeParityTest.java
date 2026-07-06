package io.github.parkkevinsb.flower.agent.runtime.flow;

import io.github.parkkevinsb.flower.agent.runtime.ActionDefinition;
import io.github.parkkevinsb.flower.agent.runtime.ActionEffect;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutionResult;
import io.github.parkkevinsb.flower.agent.runtime.ActionExecutor;
import io.github.parkkevinsb.flower.agent.runtime.ActionInputValidator;
import io.github.parkkevinsb.flower.agent.runtime.ActionOrigin;
import io.github.parkkevinsb.flower.agent.runtime.ActionProposal;
import io.github.parkkevinsb.flower.agent.runtime.ActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.ActionRiskLevel;
import io.github.parkkevinsb.flower.agent.runtime.ActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.AuditEvent;
import io.github.parkkevinsb.flower.agent.runtime.AuditSink;
import io.github.parkkevinsb.flower.agent.runtime.DefaultActionRuntime;
import io.github.parkkevinsb.flower.agent.runtime.DuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.agent.runtime.InMemoryActionRegistry;
import io.github.parkkevinsb.flower.agent.runtime.InMemoryDuplicateActionPolicy;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecision;
import io.github.parkkevinsb.flower.agent.runtime.PolicyDecisionType;
import io.github.parkkevinsb.flower.agent.runtime.PolicyGate;
import io.github.parkkevinsb.flower.agent.runtime.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the direct runtime and the Flower Flow backend are behaviorally identical.
 *
 * <p>Both are driven with the same proposals and collaborators; the resulting outcome and the full audit trail
 * (event types + payloads, minus volatile ids) must match. This guards the single-source-of-truth invariant:
 * neither backend may drift in governance behavior or audit output.</p>
 */
class RuntimeParityTest {

    @Test
    void successPathParity() {
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 10))),
                null,
                PolicyGate.allowAll(),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of("siteId", 1), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void unknownActionParity() {
        assertParity(
                () -> new InMemoryActionRegistry(List.of()),
                null,
                null,
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("UnknownAction", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void validationFailureParity() {
        ActionInputValidator invalid = (proposal, definition, context) -> ValidationResult.invalid("missing siteId");
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                PolicyGate.allowAll(),
                invalid,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void aiPlannerApprovalParity() {
        assertParity(
                () -> registryOf(writeAction("UpdateReport", Set.of(ActionOrigin.AI_PLANNER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                null,
                null,
                runtime -> runtime.handle(
                        new ActionProposal("proposal-1", "UpdateReport", ActionOrigin.AI_PLANNER,
                                "planner", "update", 0.9d, Map.of(), null, Map.of()),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void policyDenyParity() {
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of())),
                null,
                PolicyGate.denyAll("blocked by policy"),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void executorExceptionParity() {
        assertParity(
                () -> new InMemoryActionRegistry(List.of(
                        new ThrowingExecutor(writeAction("CreateReport", Set.of(ActionOrigin.USER)), "boom"))),
                null,
                PolicyGate.allowAll(),
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void dryRunParity() {
        PolicyGate dryRun = (proposal, definition, context) ->
                new PolicyDecision(PolicyDecisionType.REQUIRE_DRY_RUN, "dry run first", Map.of());
        assertParity(
                () -> registryOf(writeAction("CreateReport", Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("reportId", 7))),
                null,
                dryRun,
                null,
                runtime -> runtime.handle(
                        ActionProposal.user("CreateReport", Map.of(), "user-1"),
                        ExecutionContext.of("tenant-1", "user-1")));
    }

    @Test
    void duplicateReturnExistingParity() {
        ExecutionContext context = ExecutionContext.of("tenant-1", "user-1");
        ActionProposal first = new ActionProposal("proposal-1", "ReadStatus", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        ActionProposal second = new ActionProposal("proposal-2", "ReadStatus", ActionOrigin.USER,
                "user-1", "", 1.0d, Map.of(), "same-key", Map.of());
        assertParity(
                () -> new InMemoryActionRegistry(List.of(new StubExecutor(
                        definition("ReadStatus", ActionEffect.READ_ONLY, Set.of(ActionOrigin.USER)),
                        ActionExecutionResult.succeeded(Map.of("status", "ok"))))),
                InMemoryDuplicateActionPolicy::new,
                null,
                null,
                runtime -> {
                    runtime.handle(first, context);
                    return runtime.handle(second, context);
                });
    }

    // --- parity harness -------------------------------------------------------------------------

    private void assertParity(
            Supplier<ActionRegistry> registry,
            Supplier<DuplicateActionPolicy> duplicatePolicy,
            PolicyGate policyGate,
            ActionInputValidator validator,
            Function<ActionRuntime, ActionExecutionResult> body) {
        RecordingAuditSink directAudit = new RecordingAuditSink();
        ActionRuntime direct = new DefaultActionRuntime(
                registry.get(), validator, policyGate, null, supply(duplicatePolicy), directAudit, null);
        ActionExecutionResult directResult = body.apply(direct);

        RecordingAuditSink flowAudit = new RecordingAuditSink();
        ActionRuntime flow = new FlowActionRuntime(
                registry.get(), validator, policyGate, null, supply(duplicatePolicy), flowAudit, null, null, null, 64);
        ActionExecutionResult flowResult = body.apply(flow);

        assertThat(flowResult.status()).isEqualTo(directResult.status());
        assertThat(flowResult.message()).isEqualTo(directResult.message());
        assertThat(stripVolatile(flowResult.output())).isEqualTo(stripVolatile(directResult.output()));
        assertThat(project(flowAudit.events())).isEqualTo(project(directAudit.events()));
    }

    private static DuplicateActionPolicy supply(Supplier<DuplicateActionPolicy> supplier) {
        return supplier == null ? null : supplier.get();
    }

    private static List<Map<String, Object>> project(List<AuditEvent> events) {
        List<Map<String, Object>> projected = new ArrayList<>();
        for (AuditEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.type());
            row.put("payload", stripVolatile(event.payload()));
            projected.add(row);
        }
        return projected;
    }

    private static Map<String, Object> stripVolatile(Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>(payload);
        copy.remove("approvalId");
        return copy;
    }

    private static ActionRegistry registryOf(ActionDefinition definition, ActionExecutionResult result) {
        return new InMemoryActionRegistry(List.of(new StubExecutor(definition, result)));
    }

    private static ActionDefinition writeAction(String actionId, Set<ActionOrigin> allowedOrigins) {
        return definition(actionId, ActionEffect.WRITE, allowedOrigins);
    }

    private static ActionDefinition definition(
            String actionId, ActionEffect effect, Set<ActionOrigin> allowedOrigins) {
        return new ActionDefinition(actionId, actionId, "", effect, ActionRiskLevel.MEDIUM,
                allowedOrigins, Set.of(), false, false, true, "", "", Map.of());
    }

    private record StubExecutor(ActionDefinition definition, ActionExecutionResult result) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            return result;
        }
    }

    private record ThrowingExecutor(ActionDefinition definition, String message) implements ActionExecutor {
        @Override
        public ActionExecutionResult execute(ActionExecutionContext context) {
            throw new RuntimeException(message);
        }
    }

    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> events() {
            return events;
        }
    }
}
