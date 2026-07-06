package io.github.parkkevinsb.flower.agent.runtime;

public interface ResumableActionRuntime extends ActionRuntime {
    ActionExecutionResult resume(String runId, ApprovalDecision decision);
}
