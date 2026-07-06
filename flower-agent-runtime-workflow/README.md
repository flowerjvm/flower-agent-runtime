# flower-agent-runtime-workflow

Workflow backend for `flower-agent-runtime-core`, implemented with Flower Flow.

This module turns a controlled `ActionProposal` into a small Flower `Flow`:

```text
record-proposal
-> reserve-duplicate
-> resolve-action
-> validate-input
-> evaluate-policy
-> execute-action
-> record-result
```

The purpose of this backend is **observability**: it makes each control stage
visible as a Flower `Step` so `Engine.dump()`, the console, and lifecycle
listeners can show which stage an action is at.

It is not a durable-wait backend. The gate stages contain no `stay()`/waiting, so
this backend drives the shared `ActionPipeline` as a synchronous sequencer and is
behaviorally identical to `DefaultActionRuntime` (enforced by parity tests). It
does not suspend, wait for approval across time, or recover across restart.

Do not build human-approval, long external waits, or resume-after-restart on this
backend - those belong to the future `flower-agent-runtime-eventloop` durable-wait
backend. See `docs/architecture/EXECUTION_BACKEND_STRATEGY.md` (Backend Layering).

## Current Recovery Boundary

This module currently makes the action pipeline visible as Flower Steps, but it
does not yet provide full durable recovery.

`ActionRun` now provides the first persisted execution spine through `RunStore`,
but `ActionExecutionSession` is still an in-memory Java object and this backend
still drains synchronously. A future durable backend must split that session into:

- snapshot state: proposal, execution context, duplicate decision, policy
  decision, and result
- re-injected collaborators: registry, validator, policy gate, approval gate,
  duplicate policy, audit sink, and trace sink

That future version should use Flower durable steps and Flow persistence, and
must define idempotency/checkpoint boundaries so side-effecting action
execution is not replayed accidentally after restart.

Fatal JVM-level `Error`s are rethrown instead of converted to action results.
If a host application uses a persistent duplicate policy, it should reconcile
stale reservations on startup because fatal failures may bypass normal release
bookkeeping.

This module does not define the public action model. The public contracts live
in `flower-agent-runtime-core`.
