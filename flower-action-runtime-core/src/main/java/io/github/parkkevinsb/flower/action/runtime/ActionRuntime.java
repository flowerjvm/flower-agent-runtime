package io.github.parkkevinsb.flower.action.runtime;

public interface ActionRuntime {
    ActionExecutionResult handle(ActionProposal proposal, ExecutionContext context);
}
