package io.github.parkkevinsb.flower.action.runtime;


import io.github.parkkevinsb.flower.action.runtime.approval.ApprovalDecision;
public interface ResumableActionRuntime extends ActionRuntime {
    ActionExecutionResult resume(String runId, ApprovalDecision decision);
}
