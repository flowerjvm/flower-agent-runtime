package io.github.parkkevinsb.flower.action.runtime.validation;


import io.github.parkkevinsb.flower.action.runtime.ActionProposal;
import io.github.parkkevinsb.flower.action.runtime.ExecutionContext;
import io.github.parkkevinsb.flower.action.runtime.action.ActionDefinition;
public interface ActionInputValidator {
    ValidationResult validate(ActionProposal proposal, ActionDefinition definition, ExecutionContext context);

    static ActionInputValidator allowAll() {
        return (proposal, definition, context) -> ValidationResult.ok();
    }
}
