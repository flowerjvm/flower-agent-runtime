# flower-action-runtime-core

Pure core contracts for controlled business action execution.

This module intentionally has no dependency on Flower core, Spring, MCP,
provider SDKs, JSON libraries, or AI frameworks.

The first responsibility is the shared action boundary:

```text
ActionProposal
-> ActionRegistry
-> ActionInputValidator
-> PolicyGate
-> optional ApprovalGate
-> ActionExecutor
-> AuditSink / TraceSink
```

These stages live in an engine-neutral `ActionPipeline` over a shared
`ActionExecutionSession`. `DefaultActionRuntime` (direct, synchronous) runs them
in-thread and is the **reference implementation** of the envelope semantics
(policy, approval, audit, idempotency, failure handling). Any execution backend
must run the same stages and stay in behavioral parity with the direct runtime;
an engine is only a driver and must not carry governance logic.

Flower Flow execution belongs in `flower-action-runtime-workflow`, which drives
these same stages to make them observable. For how the backends relate - and why
durable waiting/approval-resume is a future event-loop backend's job rather than
the Flow backend's - see `docs/architecture/EXECUTION_BACKEND_STRATEGY.md`
(Backend Layering).

Feedback/control behavior belongs in a later optional module such as
`flower-action-runtime-control` after host applications prove repeated patterns.
