package io.github.parkkevinsb.flower.agent.runtime;

import java.util.List;
import java.util.Map;

/**
 * The controlled action pipeline: the single source of truth for stage order, branching, and audit payloads.
 *
 * <pre>
 * record-proposal
 * -&gt; reserve-duplicate
 * -&gt; resolve-action
 * -&gt; validate-input
 * -&gt; evaluate-policy
 * -&gt; execute-action
 * -&gt; record-result  (finalize, always runs)
 * </pre>
 *
 * <p>Gates run in order until one short-circuits; the finalize stage always runs. Every execution backend
 * (the direct {@link DefaultActionRuntime} and the Flower Flow backend) executes these same stages so their
 * governance behavior and audit trail cannot diverge.</p>
 */
public final class ActionPipeline {

    /** A pipeline stage paired with the stable name a Flow backend uses for its Step. */
    public record NamedStage(String name, ActionStage stage) {
    }

    private ActionPipeline() {
    }

    /** Ordered gate stages. Any of these may short-circuit; the finalize stage is applied separately. */
    public static List<NamedStage> gates() {
        return List.of(
                new NamedStage("record-proposal", ActionPipeline::recordProposal),
                new NamedStage("reserve-duplicate", ActionPipeline::reserveDuplicate),
                new NamedStage("resolve-action", ActionPipeline::resolveAction),
                new NamedStage("validate-input", ActionPipeline::validateInput),
                new NamedStage("evaluate-policy", ActionPipeline::evaluatePolicy),
                new NamedStage("execute-action", ActionPipeline::executeAction));
    }

    /** Terminal stage that always runs, even after a short-circuit, to finalize idempotency bookkeeping. */
    public static NamedStage finalizeStage() {
        return new NamedStage("record-result", ActionPipeline::finalizeExecution);
    }

    /** Runs the whole pipeline synchronously in the current thread and returns the resulting outcome. */
    public static ActionExecutionResult run(ActionExecutionSession session) {
        for (NamedStage gate : gates()) {
            if (gate.stage().execute(session) == StageOutcome.SHORT_CIRCUIT) {
                break;
            }
        }
        finalizeStage().stage().execute(session);
        return session.result();
    }

    static StageOutcome recordProposal(ActionExecutionSession session) {
        session.record(AuditEventType.ACTION_PROPOSED, Map.of("origin", session.proposal().origin().name()));
        return StageOutcome.CONTINUE;
    }

    static StageOutcome reserveDuplicate(ActionExecutionSession session) {
        DuplicateActionDecision decision =
                session.duplicateActionPolicy().reserve(session.proposal(), session.context());
        session.duplicateDecision(decision);
        if (decision.type() == DuplicateActionDecisionType.ACCEPT) {
            return StageOutcome.CONTINUE;
        }
        session.record(AuditEventType.ACTION_DUPLICATE, Map.of("decision", decision.type().name()));
        session.result(decision.type() == DuplicateActionDecisionType.RETURN_EXISTING
                ? decision.existingResult()
                : ActionExecutionResult.denied(decision.reason()));
        return StageOutcome.SHORT_CIRCUIT;
    }

    static StageOutcome resolveAction(ActionExecutionSession session) {
        ActionExecutor executor = session.registry().findExecutor(session.proposal().actionId()).orElse(null);
        if (executor == null) {
            ActionExecutionResult result =
                    ActionExecutionResult.denied("Action is not registered: " + session.proposal().actionId());
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        session.executor(executor);
        session.definition(executor.definition());
        session.record(AuditEventType.ACTION_RESOLVED, Map.of(
                "riskLevel", executor.definition().riskLevel().name(),
                "effect", executor.definition().effect().name()));
        return StageOutcome.CONTINUE;
    }

    static StageOutcome validateInput(ActionExecutionSession session) {
        ValidationResult validation =
                session.inputValidator().validate(session.proposal(), session.definition(), session.context());
        session.validationResult(validation);
        session.record(AuditEventType.VALIDATION_COMPLETED, Map.of("valid", validation.valid()));
        if (!validation.valid()) {
            ActionExecutionResult result =
                    ActionExecutionResult.validationFailed(String.join("; ", validation.violations()));
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome evaluatePolicy(ActionExecutionSession session) {
        PolicyDecision decision =
                session.policyGate().evaluate(session.proposal(), session.definition(), session.context());
        session.policyDecision(decision);
        session.record(AuditEventType.POLICY_EVALUATED, Map.of("type", decision.type().name()));
        if (decision.requiresApproval()) {
            ApprovalRequest approval = session.approvalGate()
                    .requestApproval(session.proposal(), session.definition(), session.context(), decision);
            session.result(ActionExecutionResult.pendingApproval(
                    decision.reason(),
                    Map.of("approvalId", approval.approvalId())));
            session.record(AuditEventType.APPROVAL_REQUESTED, Map.of("approvalId", approval.approvalId()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        if (!decision.allowedToExecuteNow()) {
            ActionExecutionResult result = ActionExecutionResult.denied(decision.reason());
            session.result(result);
            session.record(AuditEventType.ACTION_DENIED, Map.of("reason", result.message()));
            return StageOutcome.SHORT_CIRCUIT;
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome executeAction(ActionExecutionSession session) {
        ActionExecutionContext actionContext = new ActionExecutionContext(
                session.context(),
                session.proposal(),
                session.definition(),
                session.proposal().input());
        if (session.policyDecision().type() == PolicyDecisionType.REQUIRE_DRY_RUN) {
            ActionExecutionResult dryRun = session.executor().dryRun(actionContext);
            session.record(AuditEventType.DRY_RUN_COMPLETED, dryRun.output());
        }
        session.record(AuditEventType.ACTION_EXECUTION_STARTED, Map.of());
        try {
            ActionExecutionResult result = session.executor().execute(actionContext);
            session.result(result);
            session.record(result.terminalSuccess()
                    ? AuditEventType.ACTION_EXECUTION_COMPLETED
                    : AuditEventType.ACTION_EXECUTION_FAILED, result.output());
        } catch (RuntimeException exception) {
            ActionExecutionResult result = ActionExecutionResult.failed(
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            session.result(result);
            session.record(AuditEventType.ACTION_EXECUTION_FAILED, Map.of("message", result.message()));
        }
        return StageOutcome.CONTINUE;
    }

    static StageOutcome finalizeExecution(ActionExecutionSession session) {
        if (session.duplicateDecision() == null
                || session.duplicateDecision().type() == DuplicateActionDecisionType.ACCEPT) {
            session.duplicateActionPolicy().complete(session.proposal(), session.result());
        }
        return StageOutcome.CONTINUE;
    }
}
