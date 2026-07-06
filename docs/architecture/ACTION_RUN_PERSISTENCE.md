# ActionRun Persistence Model

This document defines `ActionRun` and `RunStore`: the persisted execution record
that makes a controlled action a durable, inspectable, recoverable task rather
than a value that flows through memory and disappears when `handle(...)` returns.

It operationalizes the conceptual states in
[Controlled Action State Machine](CONTROLLED_ACTION_STATE_MACHINE.md) as a
concrete entity mapped onto the current `ActionPipeline` stages, and it defines
how that entity relates to the Flower checkpoint (see
[Execution Backend Strategy](EXECUTION_BACKEND_STRATEGY.md), Backend Layering).

## Why This Exists

Today an `ActionProposal` becomes an in-memory `ActionExecutionSession` that the
pipeline mutates and then discards. There is no persisted answer to the runtime's
own core question: *what executable work exists, where is it in its lifecycle,
and what is allowed next?*

The fix is not "decide what to store." What to store is known: **one run/task per
proposal.** The missing piece is the model itself.

```text
current:  ActionExecutionSession (in memory) -> stages mutate it -> discarded
target:   ActionRun (persisted)              -> stages update it -> survives restart
```

`ActionRun` is the **business source of truth** for execution state. It is
engine-neutral and belongs in `flower-agent-runtime-core`. Every backend (direct,
workflow, future event-loop) reads and writes the same `ActionRun`.

## Relationship To Existing Contracts

```text
ActionProposal          the request (already exists)
ActionRun               persisted execution record (this document, new, core)
RunStore                persistence SPI for ActionRun (this document, new, core)
ActionExecutionSession  becomes a transient working view over one ActionRun
ActionPipeline          each stage reads/updates the ActionRun
PolicyDecision          summarized into ActionRun (type + reason)
ApprovalRequest         approvalId stored on ActionRun
DuplicateActionPolicy   duplicateKey stored on ActionRun
ActionExecutionResult   terminal result summarized into ActionRun
Flower checkpoint        Flow position only; a recovery aid, not the truth
```

## ActionRun Entity

```text
ActionRun
  runId          stable id for this execution (primary key)
  tenantId       execution identity (from ExecutionContext)
  userId
  traceId
  actionId       which registered action
  proposalId     originating proposal
  requesterId    who/what proposed (user, planner, system, MCP, scheduler)
  origin         ActionOrigin
  input          proposal input (Map; serialized by the store)
  duplicateKey   idempotency key reserved for this run
  status         coarse business lifecycle (see ActionRunStatus)
  currentStage   fine pipeline position (stage id, e.g. "evaluate-policy")
  policyDecision compact summary: decision type + reason (+ constraints)
  approvalId     set when status = WAITING_APPROVAL
  dueAt          deadline for the current wait (approval or execution), if any
  attemptToken   idempotency guard for the execute side effect (see Resume)
  result         terminal result summary: status + message + output (Map)
  failureReason  human-readable reason for DENIED/FAILED/EXPIRED/RUNTIME_FAILED
  createdAt
  updatedAt
```

`policyDecision`, `result`, and `input` are stored as compact serializable data.
Core stays JSON-free: it holds typed fields and `Map<String,Object>`; a JDBC
`RunStore` implementation does the serialization.

### Persisted vs re-injected on resume

A durable backend must be able to rebuild a run after restart. Split the current
session accordingly:

```text
Persisted in ActionRun (the durable spine):
  runId, ids, actionId, proposalId, requester, origin, input,
  duplicateKey, status, currentStage, policyDecision summary,
  approvalId, dueAt, attemptToken, result, failureReason, timestamps

Re-derived on resume (never persisted):
  executor + definition   -> look up from ActionRegistry by actionId
  validationResult        -> re-validate (state may have changed since)
  collaborators           -> registry, policyGate, approvalGate, audit/trace
                             re-injected via DI/construction
```

Rule: the run stores **facts and decisions**; live objects (executors, gates,
sinks, subscriptions) are re-attached, never serialized.

## Two Axes: status vs currentStage

These are intentionally separate and must not be collapsed.

```text
status        coarse business lifecycle. For humans, queries, dashboards,
              recovery selection. Changes rarely.
currentStage  fine pipeline position (one of the 7 stage ids). For diagnostics
              and step-level inspection. Changes often.
```

### ActionRunStatus (lifecycle)

```text
REQUESTED         run created, proposal recorded
VALIDATING        resolving action + validating input
POLICY_EVALUATED  policy allowed execution; about to run
WAITING_APPROVAL  parked on an approval interlock (durable wait)
RUNNING           execute-action in progress (side effects may occur)
SUCCEEDED         terminal: action completed successfully
FAILED            terminal: executor failed
DENIED            terminal: unregistered / invalid input / policy denied /
                  duplicate rejected
CANCELLED         terminal: explicitly cancelled
EXPIRED           terminal: a deadline (dueAt) passed while waiting
RUNTIME_FAILED    terminal: a gate/runtime failure (validator, policy, duplicate,
                  or audit threw) - see failRuntime
```

This maps to the extended set in
[Controlled Action State Machine](CONTROLLED_ACTION_STATE_MACHINE.md):
`VALIDATING`≈`PLANNED`, `POLICY_EVALUATED`≈`POLICY_CHECKED`, `EXPIRED`≈
`TIMED_OUT`, `CANCELLED`≈`ABORTED`. Keep only the states the current pipeline can
actually produce; add dry-run/compensation states when the pipeline gains them.

## State Transitions

Basic (auto-executable, e.g. READ_ONLY):

```text
REQUESTED -> VALIDATING -> POLICY_EVALUATED -> RUNNING -> SUCCEEDED
```

Approval interlock:

```text
REQUESTED -> VALIDATING -> POLICY_EVALUATED -> WAITING_APPROVAL
   --(approved)--> RUNNING -> SUCCEEDED
   --(rejected)--> DENIED
   --(dueAt passed)--> EXPIRED
```

Denied early (unregistered / invalid / policy deny / duplicate reject):

```text
REQUESTED -> VALIDATING -> DENIED
REQUESTED -> DENIED                (duplicate REJECT at reserve-duplicate)
```

Duplicate return-existing:

```text
REQUESTED -> (RETURN_EXISTING) -> terminal, result mirrors the existing run
```

Executor failure:

```text
RUNNING -> FAILED
```

Gate/runtime failure (validator/policy/duplicate/audit threw):

```text
<any non-terminal> -> RUNTIME_FAILED
```

## RunStore SPI

```java
public interface RunStore {
    ActionRun create(ActionRun run);          // first persist (REQUESTED)
    Optional<ActionRun> find(String runId);
    void update(ActionRun run);               // persist a mutation
    List<ActionRun> findResumable(String tenantId);  // for recovery
}
```

- Default `InMemoryRunStore` for tests and the direct/synchronous path.
- `JdbcRunStore` (future, durable) in an optional module, mirroring
  `flower-persistence-jdbc`. Core defines only the SPI; no JDBC or JSON in core.
- `findResumable` returns non-terminal runs (typically `WAITING_APPROVAL`, and
  `RUNNING` runs that were interrupted) so a startup service can rebuild them.

### Mandatory durable persist points

Not every stage needs a write. For durability, these transitions **must** hit the
store before control returns to the caller:

```text
create at REQUESTED               (before any side effect)
WAITING_APPROVAL                  (before returning a parked run)
RUNNING + attemptToken            (before the execute side effect)
any terminal status               (SUCCEEDED/FAILED/DENIED/EXPIRED/...)
```

`currentStage` updates between these are optional/best-effort (observability
only). A synchronous backend may flush only the mandatory points; a durable
backend may persist more granularly.

## Stage -> ActionRun Update Mapping

Mapped onto the current `ActionPipeline` stages. "persist" = a `RunStore` write.

| Stage | ActionRun effect |
|---|---|
| (on receipt) | `create` run: status=REQUESTED, currentStage=record-proposal, ids/input/dueAt set — **persist** |
| record-proposal | (run already created here) |
| reserve-duplicate | set duplicateKey. ACCEPT: continue. RETURN_EXISTING: terminal, result mirrors existing — **persist**. REJECT: status=DENIED, failureReason="duplicate running" — **persist** |
| resolve-action | currentStage=resolve-action; status=VALIDATING. Unregistered: status=DENIED — **persist** |
| validate-input | currentStage=validate-input. Invalid: status=DENIED, failureReason=violations — **persist** |
| evaluate-policy | store policyDecision. Approval: status=WAITING_APPROVAL, approvalId, dueAt — **persist**. Deny: status=DENIED — **persist**. Allow: status=POLICY_EVALUATED |
| execute-action | status=RUNNING, write attemptToken — **persist (before side effect)**. Success: result, status=SUCCEEDED. Failure: failureReason, status=FAILED |
| record-result (finalize) | duplicate complete/release; ensure terminal status + updatedAt — **persist** |
| failRuntime | if not terminal: status=RUNTIME_FAILED, failureReason — **persist (best-effort)** |
| failFinalize | preserve existing terminal result; note failureReason — **persist (best-effort)** |

This keeps the run's state a faithful shadow of the pipeline, identical across the
direct and workflow backends (extend the parity tests to assert run states, not
just audit events).

## ActionRun vs Flower Checkpoint (dual store)

A durable workflow backend has two persisted things. They can drift on crash.

```text
ActionRun (your DB)    = business execution state: what was approved, where in
                         the lifecycle, what result. SOURCE OF TRUTH.
Flower checkpoint      = Flow position (current step id, execution context).
                         A recovery aid for resuming the Flow.
```

Rules to avoid split-brain:

```text
1. Prefer writing the ActionRun update and the Flower checkpoint in ONE database
   transaction (feasible when both use the same JDBC DataSource). Then they
   cannot diverge.
2. If they cannot be atomic, ActionRun wins. On recovery, derive/repair the Flow
   position from ActionRun.status (e.g. WAITING_APPROVAL -> re-enter the approval
   wait) rather than trusting a possibly-stale checkpoint.
3. Signals/approvals are hints. The durable decision must be re-checkable against
   ActionRun state, not based on an in-memory signal payload alone.
```

## Idempotent Resume And The RUNNING Window

Once runs are resumable, `execute-action` can be re-entered after a crash. The
danger window is between entering `RUNNING` (side effect may have committed) and
persisting the terminal result.

```text
RUNNING persisted (attemptToken written)
   -> executor side effect (write doc / call API)   <-- crash here
   -> terminal result persisted
```

On resume of a `RUNNING` run:

```text
if the side effect is known committed (attemptToken/committed marker or a domain
   check) -> mark SUCCEEDED without re-running
else -> re-run, which REQUIRES the executor to be idempotent
```

The `attemptToken` on `ActionRun` gives a durable place to record "this attempt
started/committed" so resume is not a blind re-execution. The runtime cannot make
arbitrary executors idempotent; it can only make the boundary explicit and give
hosts the token to build on. This is the durable form of the existing
`actionExecutionStarted` guard.

## Module Placement And Migration Path

```text
flower-agent-runtime-core
  ActionRun, ActionRunStatus, RunStore (SPI), InMemoryRunStore
flower-agent-runtime-workflow
  drives stages, updates ActionRun; may add Flower checkpoint later
future flower-agent-runtime-eventloop
  same ActionRun; durable await + resume from findResumable
future flower-agent-runtime-persistence-jdbc
  JdbcRunStore + schema, opt-in, host-applied SQL
```

Incremental adoption (each step ships value alone):

```text
1. Add ActionRun + RunStore(SPI) + InMemoryRunStore to core.
   ActionExecutionSession holds a reference to the ActionRun; stages update it.
   Direct + workflow backends persist to the in-memory store. Parity tests assert
   run state, not just audit.
2. Add JdbcRunStore. Now the synchronous backends are durable up to their
   terminal write (crash recovery of terminal/denied runs, run history).
3. Add real approval-wait: WAITING_APPROVAL becomes a persisted parked state with
   a resume path. This is where the event-loop backend and a suspend/resume-aware
   ActionRuntime contract become necessary (see Execution Backend Strategy).
```

Step 1 is the immediate work. It is engine-neutral, testable synchronously, and
unblocks everything after it.

## Out Of Scope For The First Cut

```text
approval resume / suspend semantics   (needs the wait feature + contract change)
compensation / rollback states
retry/attempt history beyond one attemptToken
distributed run ownership / leader election
JDBC schema and migrations
```

Add these only when a host application (ArchDox) proves the need, per the vision
docs.

## Open Questions

```text
- Does RETURN_EXISTING create a thin terminal run linked to the original, or no
  run at all? (Leaning: create a thin run for audit continuity, link existingRunId.)
- Is validationResult ever trusted across resume, or always re-validated?
  (Leaning: always re-validate; state may have changed.)
- Should currentStage be an enum shared with the pipeline stage ids, or a free
  string? (Leaning: reuse the pipeline's NamedStage names as the canonical ids.)
- Where does dueAt come from for approval waits - ActionDefinition policy, the
  PolicyDecision, or host configuration?
```
