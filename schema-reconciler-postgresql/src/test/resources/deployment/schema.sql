-- Service deployment schema. Idempotent — executed by SchemaReconciler at
-- every startup inside an advisory-locked transaction; also runnable
-- standalone:
--   psql -v ON_ERROR_STOP=1 -f schema.sql
-- Shape: dedicated key columns + data jsonb. Everything lives in its own
-- schema: svc (names are qualified).

SELECT pg_advisory_lock(hashtext('svc.schema'));

CREATE SCHEMA IF NOT EXISTS svc;

-- text::timestamptz is STABLE (unindexable); this IMMUTABLE wrapper powers the
-- expression indexes. Safe only because every stored timestamp is ISO-8601
-- with an explicit offset (app-written Instant; to_jsonb(now()) for
-- DB-owned stamps) — parsing is deterministic regardless of settings.
CREATE OR REPLACE FUNCTION svc.extract_ts(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

-- ---------------------------------------------------------------------------
-- Guards: structural invariants that columns alone cannot express. Enum value
-- domains are app-validated; these triggers keep the tree/lease shapes and the
-- LangGraph ownership rules non-bypassable.
-- ---------------------------------------------------------------------------

-- Delegation tree shape: a child carries the complete link triple + key, a
-- root carries none — no nullability bypass.
CREATE OR REPLACE FUNCTION svc.workflow_runs_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NOT ((NEW.parent_run_id IS NULL) = (NEW.root_run_id IS NULL)
          AND (NEW.parent_run_id IS NULL) = (NEW.delegation_depth = 0)
          AND (NEW.parent_run_id IS NULL OR
               (NEW.parent_step_id IS NOT NULL AND NEW.requesting_execution_id IS NOT NULL
                AND NEW.delegation_key IS NOT NULL))) THEN
    RAISE EXCEPTION 'run tree shape invalid: root/child columns must be fully populated or fully absent'
      USING ERRCODE = 'check_violation', CONSTRAINT = 'ck_workflow_runs_tree_shape';
  END IF;
  RETURN NEW;
END;
$$;

-- Provider-session lease invariants: status must agree with the lease fields.
CREATE OR REPLACE FUNCTION svc.provider_sessions_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NOT ((NEW.status = 'ACTIVE'
               AND NEW.lease_execution_id IS NULL AND NEW.data->>'lease_expires_at' IS NULL) OR
          (NEW.status = 'LEASED'
               AND NEW.lease_execution_id IS NOT NULL AND NEW.data->>'lease_expires_at' IS NOT NULL) OR
          (NEW.status = 'INVALIDATED'
               AND NEW.lease_execution_id IS NULL AND NEW.data->>'lease_expires_at' IS NULL)) THEN
    RAISE EXCEPTION 'provider session % status % inconsistent with lease fields',
        NEW.session_id, NEW.status
      USING ERRCODE = 'check_violation', CONSTRAINT = 'chk_provider_sessions_lease_invariants';
  END IF;
  RETURN NEW;
END;
$$;

-- History streams are append-only; only a transaction that sets the escape
-- GUC (run-delete / tenant-purge inside their tx) may remove rows.
CREATE OR REPLACE FUNCTION svc.workflow_history_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF current_setting('app.allow_history_delete', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'workflow_history is append-only';
    END IF;
    RETURN OLD;
END;
$$;

CREATE OR REPLACE FUNCTION svc.plan_history_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF current_setting('app.allow_history_delete', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'plan_history is append-only';
    END IF;
    RETURN OLD;
END;
$$;

-- ===========================================================================
-- Tables. Columns: tenant boundary, FKs, natural-unique keys, and the
-- state/index/CAS fields the engine locks or scans on. Everything else — all
-- payloads, texts and timestamps — lives in data jsonb and surfaces through
-- the v_* views; the database owns ids and timestamps via
-- data || jsonb_build_object('x', now()) merges.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS svc.plans (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid NOT NULL,
    plan_id    uuid NOT NULL,
    status     text NOT NULL DEFAULT 'DRAFT',
    data       jsonb NOT NULL,
    CONSTRAINT uk_plans_plan_id UNIQUE (plan_id)
);

CREATE INDEX IF NOT EXISTS idx_plans_tenant_id
    ON svc.plans (tenant_id);
CREATE INDEX IF NOT EXISTS idx_plans_repository_id
    ON svc.plans ((data->>'repository_id'));

CREATE TABLE IF NOT EXISTS svc.plan_revisions (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    plan_id         uuid NOT NULL,
    revision_number integer NOT NULL,
    idempotency_key text,
    data            jsonb NOT NULL,
    CONSTRAINT fk_plan_revisions_plan_id FOREIGN KEY (plan_id)
        REFERENCES svc.plans (plan_id),
    -- (plan_id, revision_number) is the concurrency safety net for revision
    -- allocation; (plan_id, id) backs the composite FK from review_requests.
    CONSTRAINT uk_plan_revisions_plan_id_revision UNIQUE (plan_id, revision_number),
    CONSTRAINT uk_plan_revisions_plan_id_id UNIQUE (plan_id, id)
);

-- Caller-supplied dedupe: one key per plan per caller retry; NULL keys never collide.
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_revisions_idempotency
    ON svc.plan_revisions (tenant_id, plan_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS svc.review_requests (
    id                uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL,
    review_request_id uuid NOT NULL,
    plan_id           uuid NOT NULL,
    plan_revision_id  uuid NOT NULL,
    status            text NOT NULL DEFAULT 'PENDING',
    idempotency_key   text,
    data              jsonb NOT NULL,
    CONSTRAINT fk_review_requests_plan_id FOREIGN KEY (plan_id)
        REFERENCES svc.plans (plan_id),
    -- A review request's revision must belong to its plan: enforced at the
    -- schema level via the composite FK onto plan_revisions (plan_id, id).
    CONSTRAINT fk_review_requests_plan_revision
        FOREIGN KEY (plan_id, plan_revision_id)
        REFERENCES svc.plan_revisions (plan_id, id),
    CONSTRAINT uk_review_requests_review_request_id UNIQUE (review_request_id),
    CONSTRAINT uk_review_requests_tenant_id_review_request_id UNIQUE (tenant_id, review_request_id)
);

CREATE INDEX IF NOT EXISTS idx_review_requests_tenant_id
    ON svc.review_requests (tenant_id);
CREATE INDEX IF NOT EXISTS idx_review_requests_plan_id
    ON svc.review_requests (plan_id);
CREATE INDEX IF NOT EXISTS idx_review_requests_status
    ON svc.review_requests (status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_requests_idempotency
    ON svc.review_requests (tenant_id, plan_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS svc.workflow_runs (
    id                       uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid NOT NULL,
    run_id                   uuid NOT NULL,
    workflow_type            text NOT NULL,
    status                   text NOT NULL DEFAULT 'RUNNING',
    engine                   text NOT NULL DEFAULT 'LANGGRAPH',
    review_request_id        uuid,
    plan_id                  uuid,
    parent_run_id            uuid,
    parent_step_id           uuid,
    requesting_execution_id  uuid,
    delegation_key           text,
    root_run_id              uuid,
    delegation_depth         integer NOT NULL DEFAULT 0,
    data                     jsonb NOT NULL,
    CONSTRAINT fk_workflow_runs_review_request FOREIGN KEY (review_request_id)
        REFERENCES svc.review_requests (review_request_id),
    CONSTRAINT fk_workflow_runs_plan FOREIGN KEY (plan_id)
        REFERENCES svc.plans (plan_id),
    CONSTRAINT fk_workflow_runs_parent_run
        FOREIGN KEY (tenant_id, parent_run_id)
        REFERENCES svc.workflow_runs (tenant_id, run_id),
    CONSTRAINT fk_workflow_runs_root
        FOREIGN KEY (tenant_id, root_run_id)
        REFERENCES svc.workflow_runs (tenant_id, run_id),
    CONSTRAINT uk_workflow_runs_run_id UNIQUE (run_id),
    CONSTRAINT uk_workflow_runs_tenant_id_run_id UNIQUE (tenant_id, run_id)
);

-- A run binds at most one review request.
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_runs_review_request
    ON svc.workflow_runs (review_request_id) WHERE review_request_id IS NOT NULL;
-- One materialized child run per (requesting step, delegation key): the
-- idempotency key for retried settlements and the dedupe key for
-- duplicate-key denials.
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_runs_parent_step_delegation
    ON svc.workflow_runs (tenant_id, parent_step_id, delegation_key)
    WHERE parent_step_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_runs_tenant_id
    ON svc.workflow_runs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_status
    ON svc.workflow_runs (status);
CREATE INDEX IF NOT EXISTS idx_workflow_runs_parent
    ON svc.workflow_runs (tenant_id, parent_run_id) WHERE parent_run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_runs_root
    ON svc.workflow_runs (tenant_id, root_run_id) WHERE root_run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_runs_deadline
    ON svc.workflow_runs ((svc.extract_ts(data->>'deadline_at')))
    WHERE data->>'deadline_at' IS NOT NULL AND status IN ('RUNNING', 'WAITING');

DROP TRIGGER IF EXISTS trg_workflow_runs_guard ON svc.workflow_runs;
CREATE TRIGGER trg_workflow_runs_guard BEFORE INSERT OR UPDATE ON svc.workflow_runs
    FOR EACH ROW EXECUTE FUNCTION svc.workflow_runs_guard();

CREATE TABLE IF NOT EXISTS svc.workflow_steps (
    id                   uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id            uuid NOT NULL,
    run_id               uuid NOT NULL,
    seq                  integer NOT NULL,
    kind                 text NOT NULL,
    state                text NOT NULL DEFAULT 'PENDING_DISPATCH',
    review_request_id    uuid,
    review_round         integer,
    current_execution_id uuid,
    step_key             text,
    dispatch_attempts    integer NOT NULL DEFAULT 0,
    data                 jsonb NOT NULL,
    CONSTRAINT fk_workflow_steps_run FOREIGN KEY (run_id)
        REFERENCES svc.workflow_runs (run_id),
    CONSTRAINT uk_workflow_steps_run_seq UNIQUE (run_id, seq),
    -- (tenant_id, run_id, id) backs the composite FK from runs' parent_step:
    -- a child run's parent step provably belongs to the parent run.
    CONSTRAINT uk_workflow_steps_tenant_run_id UNIQUE (tenant_id, run_id, id)
);

CREATE INDEX IF NOT EXISTS idx_workflow_steps_tenant_id
    ON svc.workflow_steps (tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_steps_run_id
    ON svc.workflow_steps (run_id);
CREATE INDEX IF NOT EXISTS idx_workflow_steps_state
    ON svc.workflow_steps (state);
CREATE INDEX IF NOT EXISTS idx_workflow_steps_dispatch
    ON svc.workflow_steps (state, (svc.extract_ts(data->>'claimed_at')))
    WHERE state IN ('PENDING_DISPATCH', 'DISPATCHING');
CREATE INDEX IF NOT EXISTS idx_workflow_steps_execution
    ON svc.workflow_steps (current_execution_id) WHERE current_execution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_steps_retry
    ON svc.workflow_steps (state, (svc.extract_ts(data->>'available_at'))) WHERE state = 'RETRYING';
CREATE INDEX IF NOT EXISTS ix_workflow_steps_review
    ON svc.workflow_steps (review_request_id, review_round) WHERE review_request_id IS NOT NULL;
-- step_key NULL = engine-generated step (continuations, procedural defs).
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_steps_run_step_key
    ON svc.workflow_steps (run_id, step_key) WHERE step_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_steps_blocked
    ON svc.workflow_steps (run_id) WHERE state = 'BLOCKED';

CREATE TABLE IF NOT EXISTS svc.workflow_questions (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid NOT NULL,
    run_id     uuid NOT NULL,
    step_id    uuid NOT NULL,
    routed_to  text NOT NULL DEFAULT 'CALLER',
    status     text NOT NULL DEFAULT 'OPEN',
    data       jsonb NOT NULL,
    CONSTRAINT fk_workflow_questions_run FOREIGN KEY (run_id)
        REFERENCES svc.workflow_runs (run_id),
    CONSTRAINT fk_workflow_questions_step FOREIGN KEY (step_id)
        REFERENCES svc.workflow_steps (id)
);

CREATE INDEX IF NOT EXISTS idx_workflow_questions_run_id
    ON svc.workflow_questions (run_id);
CREATE INDEX IF NOT EXISTS idx_workflow_questions_open
    ON svc.workflow_questions (run_id) WHERE status = 'OPEN';

-- Inbox for registry outbox pushes: dedupe on (tenant_id, dedupe_key), then processed once.
CREATE TABLE IF NOT EXISTS svc.registry_events (
    id               uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id        uuid NOT NULL,
    dedupe_key       text NOT NULL,
    process_attempts integer NOT NULL DEFAULT 0,
    data             jsonb NOT NULL,
    CONSTRAINT uk_registry_events_dedupe UNIQUE (tenant_id, dedupe_key)
);

CREATE INDEX IF NOT EXISTS idx_registry_events_pending
    ON svc.registry_events
        ((svc.extract_ts(data->>'processed_at')),
         (svc.extract_ts(data->>'claimed_at')))
    WHERE data->>'processed_at' IS NULL;

-- Append-only audit. The trigger blocks UPDATE/DELETE unless the transaction
-- set the escape GUC.
CREATE TABLE IF NOT EXISTS svc.workflow_history (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid NOT NULL,
    run_id     uuid NOT NULL,
    chain_seq  bigint,
    event_type text NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT fk_workflow_history_run FOREIGN KEY (run_id)
        REFERENCES svc.workflow_runs (run_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_history_chain
    ON svc.workflow_history (tenant_id, run_id, chain_seq)
    WHERE chain_seq IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_workflow_history_tenant_id
    ON svc.workflow_history (tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflow_history_run_id
    ON svc.workflow_history (run_id);
CREATE INDEX IF NOT EXISTS idx_workflow_history_event_type
    ON svc.workflow_history (event_type);

DROP TRIGGER IF EXISTS trg_workflow_history_immutable ON svc.workflow_history;
CREATE TRIGGER trg_workflow_history_immutable
    BEFORE UPDATE OR DELETE ON svc.workflow_history
    FOR EACH ROW EXECUTE FUNCTION svc.workflow_history_immutable();

-- Plan-scoped append-only audit, chained per (tenant_id, plan_id). Dual-written
-- by the API plane and the workflow plane.
CREATE TABLE IF NOT EXISTS svc.plan_history (
    id         uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid NOT NULL,
    plan_id    uuid NOT NULL,
    chain_seq  bigint NOT NULL,
    event_type text NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT fk_plan_history_plan FOREIGN KEY (plan_id)
        REFERENCES svc.plans (plan_id),
    CONSTRAINT uk_plan_history_chain UNIQUE (tenant_id, plan_id, chain_seq)
);

DROP TRIGGER IF EXISTS trg_plan_history_immutable ON svc.plan_history;
CREATE TRIGGER trg_plan_history_immutable
    BEFORE UPDATE OR DELETE ON svc.plan_history
    FOR EACH ROW EXECUTE FUNCTION svc.plan_history_immutable();

-- First-class execution attempts. One row = one attempt: created REQUESTED and
-- agent-bound at dispatch; an eligible runner claims it (ACQUIRED, fencing
-- minted); the runner reports output (SUBMITTED) and the coordinator alone
-- settles (SETTLED) or policy-fails it. Fencing tokens come from the sequence:
-- every claim must be strictly newer than the last. Must stay under 2^53 (JS
-- clients read doubles).
CREATE SEQUENCE IF NOT EXISTS svc.fencing_seq;

CREATE TABLE IF NOT EXISTS svc.executions (
    id                     uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id              uuid NOT NULL,
    run_id                 uuid NOT NULL,
    step_id                uuid NOT NULL,
    attempt_number         integer NOT NULL,
    bound_agent_id         uuid NOT NULL,
    state                  text NOT NULL DEFAULT 'REQUESTED',
    runner_id              uuid,
    fencing_token          bigint,
    preferred_runner_id    uuid,
    progress_sampled_at_ms bigint,
    data                   jsonb NOT NULL,
    CONSTRAINT fk_executions_run FOREIGN KEY (run_id)
        REFERENCES svc.workflow_runs (run_id),
    CONSTRAINT fk_executions_step FOREIGN KEY (step_id)
        REFERENCES svc.workflow_steps (id),
    CONSTRAINT uk_executions_step_attempt UNIQUE (tenant_id, step_id, attempt_number),
    -- (tenant_id, step_id, id) backs the composite FK from runs'
    -- requesting_execution: the requesting execution provably belongs to the
    -- parent step.
    CONSTRAINT uk_executions_tenant_step_id UNIQUE (tenant_id, step_id, id)
);

CREATE INDEX IF NOT EXISTS idx_executions_tenant_id
    ON svc.executions (tenant_id);
CREATE INDEX IF NOT EXISTS idx_executions_step
    ON svc.executions (step_id);
CREATE INDEX IF NOT EXISTS idx_executions_claimable
    ON svc.executions (state, bound_agent_id, (svc.extract_ts(data->>'created_at')))
    WHERE state = 'REQUESTED';
CREATE INDEX IF NOT EXISTS idx_executions_lease
    ON svc.executions ((svc.extract_ts(data->>'lease_expires_at'))) WHERE state = 'ACQUIRED';
CREATE INDEX IF NOT EXISTS idx_executions_unsettled
    ON svc.executions ((svc.extract_ts(data->>'submitted_at'))) WHERE state = 'SUBMITTED';
CREATE INDEX IF NOT EXISTS idx_executions_runner
    ON svc.executions (runner_id) WHERE runner_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_executions_run_state
    ON svc.executions (run_id, state);
CREATE INDEX IF NOT EXISTS idx_executions_attempt_end
    ON svc.executions (state) WHERE state IN ('FAILED', 'EXPIRED');
CREATE INDEX IF NOT EXISTS idx_executions_tenant_live
    ON svc.executions (tenant_id, state)
    WHERE state IN ('REQUESTED', 'ACQUIRED', 'SUBMITTED');

-- The child-link FKs land only now: runs are created before steps/executions
-- (steps/executions FK back to runs), so the ordering cycle breaks here.
ALTER TABLE svc.workflow_runs
    DROP CONSTRAINT IF EXISTS fk_workflow_runs_parent_step,
    ADD CONSTRAINT fk_workflow_runs_parent_step
        FOREIGN KEY (tenant_id, parent_run_id, parent_step_id)
        REFERENCES svc.workflow_steps (tenant_id, run_id, id),
    DROP CONSTRAINT IF EXISTS fk_workflow_runs_requesting_execution,
    ADD CONSTRAINT fk_workflow_runs_requesting_execution
        FOREIGN KEY (tenant_id, parent_step_id, requesting_execution_id)
        REFERENCES svc.executions (tenant_id, step_id, id);

-- Digest-addressed execution artifacts (result/evidence blobs the runner uploads).
CREATE TABLE IF NOT EXISTS svc.execution_artifacts (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid NOT NULL,
    execution_id uuid NOT NULL,
    kind         text NOT NULL,
    digest       text NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT fk_execution_artifacts_execution FOREIGN KEY (execution_id)
        REFERENCES svc.executions (id),
    CONSTRAINT uk_execution_artifacts_dedupe UNIQUE (tenant_id, execution_id, kind, digest)
);

CREATE INDEX IF NOT EXISTS idx_execution_artifacts_execution
    ON svc.execution_artifacts (execution_id);

-- Execution-plane identity: the credential binding "this public key is runner
-- R". public_key is a base64-encoded X.509 SPKI (Ed25519).
CREATE TABLE IF NOT EXISTS svc.runner_identity (
    id        uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    runner_id uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT uk_runner_identity_runner UNIQUE (tenant_id, runner_id)
);

-- Async fact views maintained from registry resource events and the
-- enroll-time seed. NULL fact columns mean "not yet observed" — the serve-set
-- derivation treats them as matching nothing (conservative).
CREATE TABLE IF NOT EXISTS svc.runner_view (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid NOT NULL,
    runner_id    uuid NOT NULL,
    status       text,
    session_open boolean NOT NULL DEFAULT false,
    data         jsonb NOT NULL,
    CONSTRAINT uk_runner_view_runner UNIQUE (tenant_id, runner_id)
);

CREATE TABLE IF NOT EXISTS svc.agent_view (
    id        uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    agent_id  uuid NOT NULL,
    status    text,
    data      jsonb NOT NULL,
    CONSTRAINT uk_agent_view_agent UNIQUE (tenant_id, agent_id)
);

-- Runner-emitted progress events on an ACQUIRED execution — fenced-runner-owned,
-- deduplicated on (tenant, execution, seq); no lifecycle effect.
CREATE TABLE IF NOT EXISTS svc.execution_event (
    id           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id    uuid NOT NULL,
    execution_id uuid NOT NULL,
    seq          bigint NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT fk_execution_event_execution FOREIGN KEY (execution_id)
        REFERENCES svc.executions (id),
    CONSTRAINT uk_execution_event_seq UNIQUE (tenant_id, execution_id, seq)
);

-- Persisted provenance for model-influenced workflow decisions. One row per
-- (tenant, run, step, attempt): application is idempotent — a duplicate
-- proposal for the same attempt is ignored via the unique constraint.
CREATE TABLE IF NOT EXISTS svc.reasoning_decision (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id       uuid NOT NULL,
    run_id          uuid NOT NULL,
    step_id         uuid NOT NULL,
    attempt_number  integer NOT NULL,
    idempotency_key text NOT NULL,
    data            jsonb NOT NULL,
    CONSTRAINT fk_reasoning_decision_step FOREIGN KEY (step_id)
        REFERENCES svc.workflow_steps (id),
    CONSTRAINT uk_reasoning_decision_attempt
        UNIQUE (tenant_id, run_id, step_id, attempt_number)
);

CREATE INDEX IF NOT EXISTS idx_reasoning_decision_run
    ON svc.reasoning_decision (tenant_id, run_id);

-- Message protocol: conversations and messages, distinct from runs/executions.
-- A message is communication; the run it may cause is processing. Messages
-- carry instructions, structured context and references to external resources
-- (never resource bodies).
CREATE TABLE IF NOT EXISTS svc.conversations (
    id        uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id uuid NOT NULL,
    status    text NOT NULL DEFAULT 'OPEN',
    data      jsonb NOT NULL,
    CONSTRAINT uk_conversations_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS svc.messages (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid NOT NULL,
    conversation_id     uuid NOT NULL,
    seq                 bigint NOT NULL,
    sender_type         text NOT NULL,
    run_id              uuid,
    reply_to_message_id uuid,
    status              text NOT NULL DEFAULT 'RECEIVED',
    idempotency_key     text,
    data                jsonb NOT NULL,
    CONSTRAINT uk_messages_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_messages_conv_seq UNIQUE (tenant_id, conversation_id, seq),
    CONSTRAINT uk_messages_idem UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (tenant_id, conversation_id)
        REFERENCES svc.conversations (tenant_id, id),
    -- Cross-tenant reply_to is impossible at the row level too: the composite
    -- FK forces the referenced message to share this message's tenant.
    CONSTRAINT fk_messages_reply_to FOREIGN KEY (tenant_id, reply_to_message_id)
        REFERENCES svc.messages (tenant_id, id),
    CONSTRAINT fk_messages_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES svc.workflow_runs (tenant_id, run_id)
);

-- Per-tenant caps the run input may only narrow, never widen.
CREATE TABLE IF NOT EXISTS svc.tenant_policy (
    tenant_id uuid PRIMARY KEY,
    data      jsonb NOT NULL
);

-- One row per ROOT run, created in the same transaction as the root run row.
-- Counters are monotonic CONSUMPTION records (never released); live concurrency
-- is reconciled from execution rows, not counted here. usage = tree-wide
-- settled usage rollup.
CREATE TABLE IF NOT EXISTS svc.budget_ledger (
    tenant_id        uuid NOT NULL,
    root_run_id      uuid PRIMARY KEY,
    steps_created    integer NOT NULL DEFAULT 0,
    attempts_created integer NOT NULL DEFAULT 0,
    children_created integer NOT NULL DEFAULT 0,
    data             jsonb NOT NULL,
    CONSTRAINT fk_budget_ledger_root
        FOREIGN KEY (tenant_id, root_run_id)
        REFERENCES svc.workflow_runs (tenant_id, run_id)
);

-- Multi-repository review binding: the full {repositoryId, remoteUrl, branch,
-- commitSha} set a plan is bound to. ordinal 0 is the primary binding.
CREATE TABLE IF NOT EXISTS svc.plan_repositories (
    id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id     uuid NOT NULL,
    plan_id       uuid NOT NULL,
    ordinal       integer NOT NULL,
    repository_id text NOT NULL,
    data          jsonb NOT NULL,
    CONSTRAINT fk_plan_repositories_plan_id FOREIGN KEY (plan_id)
        REFERENCES svc.plans (plan_id),
    CONSTRAINT uk_plan_repositories_plan_id_ordinal UNIQUE (plan_id, ordinal),
    CONSTRAINT uk_plan_repositories_plan_id_repo UNIQUE (plan_id, repository_id)
);

CREATE INDEX IF NOT EXISTS idx_plan_repositories_tenant_id
    ON svc.plan_repositories (tenant_id);

-- Transactional outbox for LangGraph progression with monotonic per-run
-- sequence.
CREATE TABLE IF NOT EXISTS svc.workflow_transitions (
    id             uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid NOT NULL,
    run_id         uuid NOT NULL,
    seq            integer NOT NULL,
    transition_key text NOT NULL,
    state          text NOT NULL DEFAULT 'PENDING',
    claim_fence    bigint NOT NULL DEFAULT 0,
    attempts       integer NOT NULL DEFAULT 0,
    data           jsonb NOT NULL,
    CONSTRAINT fk_workflow_transitions_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES svc.workflow_runs (tenant_id, run_id) ON DELETE RESTRICT,
    CONSTRAINT uk_workflow_transitions_run_key UNIQUE (tenant_id, run_id, transition_key),
    CONSTRAINT uk_workflow_transitions_run_seq UNIQUE (tenant_id, run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_workflow_transitions_reconciler
    ON svc.workflow_transitions (state, (svc.extract_ts(data->>'created_at')))
    WHERE state IN ('PENDING', 'DISPATCHING');

-- Opt-in provider session reuse for conversations and plan reviews.
CREATE TABLE IF NOT EXISTS svc.provider_sessions (
    id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid NOT NULL,
    session_id          uuid NOT NULL,
    provider            text NOT NULL,
    scope_type          text NOT NULL,
    scope_id            text NOT NULL,
    scope_sub_id        text,
    status              text NOT NULL DEFAULT 'ACTIVE',
    runner_id           uuid,
    lease_execution_id  uuid,
    fencing_token       bigint NOT NULL DEFAULT 1,
    last_scope_seq      bigint NOT NULL DEFAULT 0,
    data                jsonb NOT NULL,
    CONSTRAINT uk_provider_sessions_session_id UNIQUE (session_id)
);

-- Canonical active or leased session per (tenant_id, provider, scope_type,
-- scope_id, scope_sub_id)
CREATE UNIQUE INDEX IF NOT EXISTS uk_provider_sessions_canonical_live
    ON svc.provider_sessions (tenant_id, provider, scope_type, scope_id, COALESCE(scope_sub_id, ''))
    WHERE status IN ('ACTIVE', 'LEASED');

CREATE INDEX IF NOT EXISTS idx_provider_sessions_expired_leases
    ON svc.provider_sessions ((svc.extract_ts(data->>'lease_expires_at')))
    WHERE status = 'LEASED';

CREATE INDEX IF NOT EXISTS idx_provider_sessions_lease_execution_id
    ON svc.provider_sessions (tenant_id, lease_execution_id)
    WHERE status = 'LEASED';

DROP TRIGGER IF EXISTS trg_provider_sessions_guard ON svc.provider_sessions;
CREATE TRIGGER trg_provider_sessions_guard BEFORE INSERT OR UPDATE ON svc.provider_sessions
    FOR EACH ROW EXECUTE FUNCTION svc.provider_sessions_guard();

-- ===========================================================================
-- LangGraph progression ownership: the guard makes every write that mutates
-- graph state carry the app.authoritative_engine session GUC; the stored
-- function sets it only after validating the outbox transition under row locks.
-- ===========================================================================

CREATE OR REPLACE FUNCTION svc.enforce_langgraph_progression()
RETURNS trigger AS $$
DECLARE
    run_engine TEXT;
    target_tenant_id UUID;
    target_run_id UUID;
BEGIN
    IF TG_TABLE_NAME = 'workflow_runs' THEN
        run_engine := OLD.engine;
        target_tenant_id := OLD.tenant_id;
        target_run_id := OLD.run_id;

        IF run_engine = 'LANGGRAPH' THEN
            -- Invariant 1: Engine is strictly immutable
            IF NEW.engine <> 'LANGGRAPH' THEN
                RAISE EXCEPTION 'Run engine immutable: run % cannot regress from LANGGRAPH to %', target_run_id, NEW.engine;
            END IF;

            -- Invariant 2: Terminal runs cannot be rewritten
            IF OLD.status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND NEW.status <> OLD.status THEN
                RAISE EXCEPTION 'Terminal run immutable: run % in status % cannot transition to %', target_run_id, OLD.status, NEW.status;
            END IF;

            -- Invariant 3: Progression writes require authoritative session parameter
            IF (NEW.data->'output' IS DISTINCT FROM OLD.data->'output')
               OR (NEW.status = 'COMPLETED')
               OR (OLD.status = 'WAITING' AND NEW.status = 'RUNNING')
            THEN
                IF current_setting('app.authoritative_engine', true) IS DISTINCT FROM 'LANGGRAPH' THEN
                    RAISE EXCEPTION 'Progression write forbidden on LangGraph run %: status % -> %, output modified. Session lacks app.authoritative_engine = ''LANGGRAPH''.',
                        target_run_id, OLD.status, NEW.status;
                END IF;
            END IF;

            -- Invariant 4: Control-plane writes (CANCELLED, FAILED) cannot forge output
            IF (NEW.status IN ('CANCELLED', 'FAILED')) AND (NEW.data->'output' IS DISTINCT FROM OLD.data->'output') THEN
                IF current_setting('app.authoritative_engine', true) IS DISTINCT FROM 'LANGGRAPH' THEN
                    RAISE EXCEPTION 'Control-plane lifecycle write cannot modify output on LangGraph run %.', target_run_id;
                END IF;
            END IF;
        END IF;

    ELSIF TG_TABLE_NAME = 'workflow_steps' THEN
        target_tenant_id := COALESCE(NEW.tenant_id, OLD.tenant_id);
        target_run_id := COALESCE(NEW.run_id, OLD.run_id);

        -- Strict tenant-aware run binding:
        SELECT engine INTO run_engine
        FROM svc.workflow_runs
        WHERE tenant_id = target_tenant_id AND run_id = target_run_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Step refers to non-existent run % for tenant %', target_run_id, target_tenant_id;
        END IF;

        IF run_engine = 'LANGGRAPH' THEN
            IF TG_OP = 'INSERT' THEN
                -- Dynamic step insertion requires the progression authority:
                IF current_setting('app.authoritative_engine', true) IS DISTINCT FROM 'LANGGRAPH' THEN
                    RAISE EXCEPTION 'Step insertion forbidden on LangGraph run %: session lacks app.authoritative_engine = ''LANGGRAPH''.', target_run_id;
                END IF;
            ELSIF TG_OP = 'UPDATE' THEN
                -- Terminal steps immutable:
                IF OLD.state IN ('SETTLED', 'SKIPPED', 'CANCELLED') AND NEW.state <> OLD.state THEN
                    RAISE EXCEPTION 'Terminal step % is immutable (current: %)', OLD.id, OLD.state;
                END IF;

                -- DAG progression mutations require the session GUC:
                IF (OLD.state = 'BLOCKED' AND NEW.state IN ('READY', 'PENDING_DISPATCH', 'SKIPPED', 'FAILED'))
                   OR (OLD.state = 'PENDING_DISPATCH' AND NEW.state = 'SKIPPED')
                   OR (OLD.state = 'BLOCKED' AND NEW.data->'spec' IS DISTINCT FROM OLD.data->'spec')
                THEN
                    IF current_setting('app.authoritative_engine', true) IS DISTINCT FROM 'LANGGRAPH' THEN
                        RAISE EXCEPTION 'Progression step update forbidden on LangGraph run %: step % state % -> % without app.authoritative_engine = ''LANGGRAPH''.',
                            target_run_id, OLD.id, OLD.state, NEW.state;
                    END IF;
                END IF;
                -- Permitted control-plane writes (SETTLED, FAILED attempt,
                -- CANCELLED, DISPATCHED) pass.
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_workflow_runs_langgraph_guard ON svc.workflow_runs;
CREATE TRIGGER trg_workflow_runs_langgraph_guard
    BEFORE UPDATE ON svc.workflow_runs
    FOR EACH ROW EXECUTE FUNCTION svc.enforce_langgraph_progression();

DROP TRIGGER IF EXISTS trg_workflow_steps_langgraph_guard ON svc.workflow_steps;
CREATE TRIGGER trg_workflow_steps_langgraph_guard
    BEFORE INSERT OR UPDATE ON svc.workflow_steps
    FOR EACH ROW EXECUTE FUNCTION svc.enforce_langgraph_progression();

-- Atomic progression materialization: validates under row locks, authorizes,
-- applies step promotions/inserts and the run status/output update, then
-- commits the outbox transition last.
CREATE OR REPLACE FUNCTION svc.apply_langgraph_progression(
    p_tenant_id UUID,
    p_run_id UUID,
    p_transition_id UUID,
    p_expected_seq INT,
    p_expected_fence BIGINT,
    p_response_payload JSONB,
    p_run_status TEXT,       -- Target run status: 'COMPLETED', 'WAITING', 'FAILED', or NULL (unchanged)
    p_set_output BOOLEAN,    -- If true, explicitly set output = p_run_output (even if NULL)
    p_run_output JSONB,      -- Business output yielded by LangGraph
    p_step_promotions JSONB, -- JSON array: [{"step_id": UUID, "new_state": TEXT, "spec": JSONB}]
    p_step_inserts JSONB DEFAULT '[]'::jsonb -- JSON array: [{"seq": INT, "kind": TEXT, "role": TEXT, "state": TEXT, "spec": JSONB, "step_key": TEXT, "review_round": INT, "review_request_id": UUID}]
) RETURNS VOID AS $$
DECLARE
    v_transition_rec RECORD;
    v_run_rec RECORD;
    v_step_elem JSONB;
    v_step_id UUID;
    v_new_state TEXT;
    v_step_spec JSONB;
    v_current_step_state TEXT;
    v_rows_affected INT;
    v_insert_elem JSONB;
    v_ins_seq INT;
    v_ins_kind TEXT;
    v_ins_role TEXT;
    v_ins_state TEXT;
    v_ins_spec JSONB;
    v_ins_step_key TEXT;
    v_ins_rev_round INT;
    v_ins_rev_req_id UUID;
    v_existing_step RECORD;
BEGIN
    -- =========================================================================
    -- 1. VALIDATION UNDER ROW LOCKS FIRST (Before setting any GUC)
    -- =========================================================================

    -- 1a. Validate run exists, matches tenant, and engine is LANGGRAPH:
    SELECT * INTO v_run_rec
    FROM svc.workflow_runs
    WHERE tenant_id = p_tenant_id AND run_id = p_run_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Run % for tenant % not found', p_run_id, p_tenant_id;
    END IF;

    IF v_run_rec.engine <> 'LANGGRAPH' THEN
        RAISE EXCEPTION 'Run % is not owned by LangGraph (engine: %)', p_run_id, v_run_rec.engine;
    END IF;

    -- 1b. Validate outbox transition exists, matches tenant, run, seq, fence, and state:
    SELECT * INTO v_transition_rec
    FROM svc.workflow_transitions
    WHERE id = p_transition_id
      AND tenant_id = p_tenant_id
      AND run_id = p_run_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transition % does not match tenant % and run %', p_transition_id, p_tenant_id, p_run_id;
    END IF;

    IF v_transition_rec.seq <> p_expected_seq THEN
        RAISE EXCEPTION 'Transition % sequence mismatch: expected %, got %', p_transition_id, p_expected_seq, v_transition_rec.seq;
    END IF;

    IF v_transition_rec.claim_fence <> p_expected_fence THEN
        RAISE EXCEPTION 'Transition % claim fence mismatch: expected %, current %', p_transition_id, p_expected_fence, v_transition_rec.claim_fence;
    END IF;

    IF v_transition_rec.state <> 'DISPATCHING' THEN
        RAISE EXCEPTION 'Transition % not in DISPATCHING state (current: %)', p_transition_id, v_transition_rec.state;
    END IF;

    -- 1c. Sequence Predecessor Invariant: All earlier transitions for this run MUST be COMMITTED:
    IF EXISTS (
        SELECT 1 FROM svc.workflow_transitions
        WHERE tenant_id = p_tenant_id
          AND run_id = p_run_id
          AND seq < p_expected_seq
          AND state <> 'COMMITTED'
    ) THEN
        RAISE EXCEPTION 'Sequence predecessor invariant violated: earlier transitions for run % must be COMMITTED before seq % can materialize',
            p_run_id, p_expected_seq;
    END IF;

    -- 1d. Handle racing cancellation: if run was cancelled in flight, expire outbox row and drop projections:
    IF v_run_rec.status = 'CANCELLED' THEN
        UPDATE svc.workflow_transitions
        SET state = 'EXPIRED',
            data = data || jsonb_build_object('last_error', 'Run cancelled while transition in flight', 'updated_at', now())
        WHERE id = p_transition_id
          AND claim_fence = p_expected_fence
          AND state = 'DISPATCHING';

        GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
        IF v_rows_affected <> 1 THEN
            RAISE EXCEPTION 'Claim fence lost during cancellation expiration for transition %', p_transition_id;
        END IF;
        RETURN;
    END IF;

    -- 1e. Validate legal run status transition:
    IF p_run_status IS NOT NULL THEN
        IF v_run_rec.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
            RAISE EXCEPTION 'Illegal status transition: cannot update terminal run % (current status %)', p_run_id, v_run_rec.status;
        END IF;

        IF p_run_status NOT IN ('COMPLETED', 'WAITING', 'RUNNING', 'FAILED') THEN
            RAISE EXCEPTION 'Illegal target status % for progression on run %', p_run_status, p_run_id;
        END IF;
    END IF;

    -- =========================================================================
    -- 2. AUTHORIZE PROGRESSION WRITES (Set session GUC only after validation)
    -- =========================================================================
    PERFORM set_config('app.authoritative_engine', 'LANGGRAPH', true);

    -- =========================================================================
    -- 3. APPLY STEP PROMOTIONS (With Row Count & Legal State Checks)
    -- =========================================================================
    IF p_step_promotions IS NOT NULL AND jsonb_array_length(p_step_promotions) > 0 THEN
        FOR v_step_elem IN SELECT * FROM jsonb_array_elements(p_step_promotions)
        LOOP
            v_step_id := (v_step_elem->>'step_id')::UUID;
            v_new_state := v_step_elem->>'new_state';
            v_step_spec := v_step_elem->'spec';

            IF v_new_state = 'READY' THEN
                v_new_state := 'PENDING_DISPATCH';
            END IF;

            -- Validate step exists, belongs to tenant and run, and check current state:
            SELECT state INTO v_current_step_state
            FROM svc.workflow_steps
            WHERE tenant_id = p_tenant_id AND run_id = p_run_id AND id = v_step_id
            FOR UPDATE;

            IF NOT FOUND THEN
                RAISE EXCEPTION 'Step % does not belong to run % and tenant %', v_step_id, p_run_id, p_tenant_id;
            END IF;

            -- Legal progression promotions: BLOCKED -> PENDING_DISPATCH, SKIPPED, or FAILED:
            IF v_current_step_state <> 'BLOCKED' AND NOT (v_current_step_state = 'PENDING_DISPATCH' AND v_new_state = 'PENDING_DISPATCH') THEN
                RAISE EXCEPTION 'Illegal progression step transition: step % in state % cannot be transitioned to %',
                    v_step_id, v_current_step_state, v_new_state;
            END IF;

            IF v_new_state NOT IN ('PENDING_DISPATCH', 'SKIPPED', 'FAILED') THEN
                RAISE EXCEPTION 'Illegal target step state % for progression on step %', v_new_state, v_step_id;
            END IF;

            -- Apply step update:
            UPDATE svc.workflow_steps
            SET state = v_new_state,
                data = (CASE WHEN v_step_spec IS NOT NULL
                             THEN data || jsonb_build_object('spec', v_step_spec)
                             ELSE data END)
                       || jsonb_build_object('updated_at', now())
            WHERE tenant_id = p_tenant_id AND run_id = p_run_id AND id = v_step_id;

            GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
            IF v_rows_affected <> 1 THEN
                RAISE EXCEPTION 'Step promotion failed: expected 1 row updated for step %, got %', v_step_id, v_rows_affected;
            END IF;
        END LOOP;
    END IF;

    -- =========================================================================
    -- 4. APPLY STEP INSERTS (If Any New Steps Yielded by LangGraph)
    -- =========================================================================
    IF p_step_inserts IS NOT NULL AND jsonb_array_length(p_step_inserts) > 0 THEN
        FOR v_insert_elem IN SELECT * FROM jsonb_array_elements(p_step_inserts)
        LOOP
            v_ins_seq := (v_insert_elem->>'seq')::INT;
            v_ins_kind := COALESCE(v_insert_elem->>'kind', 'step');
            v_ins_role := v_insert_elem->>'role';
            v_ins_state := COALESCE(v_insert_elem->>'state', 'PENDING_DISPATCH');
            IF v_ins_state = 'READY' THEN
                v_ins_state := 'PENDING_DISPATCH';
            END IF;
            v_ins_spec := v_insert_elem->'spec';
            v_ins_step_key := v_insert_elem->>'step_key';
            v_ins_rev_round := (v_insert_elem->>'review_round')::INT;
            IF (v_insert_elem->>'review_request_id') IS NOT NULL THEN
                v_ins_rev_req_id := (v_insert_elem->>'review_request_id')::UUID;
            ELSE
                v_ins_rev_req_id := NULL;
            END IF;

            -- Check if step with this step_key already exists for this run:
            IF v_ins_step_key IS NOT NULL THEN
                SELECT * INTO v_existing_step
                FROM svc.workflow_steps
                WHERE tenant_id = p_tenant_id AND run_id = p_run_id AND step_key = v_ins_step_key;

                IF FOUND THEN
                    -- Idempotent check: if role and spec match, skip insertion
                    IF v_existing_step.data->>'role' = v_ins_role
                       AND (v_ins_spec IS NULL OR v_existing_step.data->'spec' = v_ins_spec) THEN
                        CONTINUE;
                    ELSE
                        RAISE EXCEPTION 'Intent conflict: step % already exists with different spec or role', v_ins_step_key;
                    END IF;
                END IF;
            END IF;

            INSERT INTO svc.workflow_steps
                (tenant_id, run_id, seq, kind, state, review_request_id, review_round, step_key, data)
            VALUES
                (p_tenant_id, p_run_id, v_ins_seq, v_ins_kind, v_ins_state,
                 v_ins_rev_req_id, v_ins_rev_round, v_ins_step_key,
                 jsonb_strip_nulls(jsonb_build_object('role', v_ins_role, 'spec', v_ins_spec))
                     || jsonb_build_object('created_at', now(), 'updated_at', now()));

            GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
            IF v_rows_affected <> 1 THEN
                RAISE EXCEPTION 'Step insertion failed for step_key %: expected 1 row inserted, got %',
                    v_ins_step_key, v_rows_affected;
            END IF;
        END LOOP;
    END IF;

    -- =========================================================================
    -- 5. APPLY RUN STATUS & OUTPUT UPDATES (With Row Count Assertions)
    -- =========================================================================
    IF p_run_status IS NOT NULL OR p_set_output THEN
        UPDATE svc.workflow_runs
        SET status = COALESCE(p_run_status, status),
            data = (CASE WHEN p_set_output
                         THEN jsonb_strip_nulls(data || jsonb_build_object('output', p_run_output))
                         ELSE data END)
                   || jsonb_build_object('updated_at', now())
        WHERE tenant_id = p_tenant_id AND run_id = p_run_id;

        GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
        IF v_rows_affected <> 1 THEN
            RAISE EXCEPTION 'Run status update failed: expected 1 row updated for run %, got %', p_run_id, v_rows_affected;
        END IF;
    END IF;

    -- =========================================================================
    -- 6. PERSIST RESPONSE PAYLOAD & COMMIT OUTBOX LAST
    -- =========================================================================
    UPDATE svc.workflow_transitions
    SET state = 'COMMITTED',
        data = data || jsonb_build_object('response_payload', p_response_payload, 'updated_at', now())
    WHERE id = p_transition_id
      AND claim_fence = p_expected_fence
      AND state = 'DISPATCHING';

    GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
    IF v_rows_affected <> 1 THEN
        RAISE EXCEPTION 'Outbox commit failed: claim fence lost or state not DISPATCHING for transition % (expected fence %)',
            p_transition_id, p_expected_fence;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- ===========================================================================
-- Typed read views: all application reads go through v_*; data fields surface
-- with their former names and types so mappers stay unchanged.
-- ===========================================================================

CREATE OR REPLACE VIEW svc.v_plans AS
SELECT id,
       tenant_id,
       plan_id,
       data->>'repository_id'                          AS repository_id,
       data->>'remote_url'                             AS remote_url,
       data->>'repository_branch'                      AS repository_branch,
       data->>'repository_commit_sha'                  AS repository_commit_sha,
       data->>'content'                                AS content,
       status,
       data->>'created_by'                             AS created_by,
       data->>'source_session_id'                      AS source_session_id,
       data->>'last_observed_commit_sha'               AS last_observed_commit_sha,
       svc.extract_ts(data->>'last_observed_at') AS last_observed_at,
       svc.extract_ts(data->>'created_at')       AS created_at,
       svc.extract_ts(data->>'updated_at')       AS updated_at
FROM svc.plans;

CREATE OR REPLACE VIEW svc.v_plan_revisions AS
SELECT id,
       tenant_id,
       plan_id,
       revision_number,
       data->>'repository_id'                          AS repository_id,
       data->>'repository_branch'                      AS repository_branch,
       data->>'repository_commit_sha'                  AS repository_commit_sha,
       data->>'working_tree_fingerprint'               AS working_tree_fingerprint,
       data->>'content'                                AS content,
       data->>'created_by'                             AS created_by,
       svc.extract_ts(data->>'created_at') AS created_at,
       data->'metadata'                                AS metadata,
       idempotency_key
FROM svc.plan_revisions;

CREATE OR REPLACE VIEW svc.v_review_requests AS
SELECT id,
       tenant_id,
       review_request_id,
       plan_id,
       plan_revision_id,
       data->>'repository_id'                          AS repository_id,
       data->>'repository_remote_url'                  AS repository_remote_url,
       data->>'repository_branch'                      AS repository_branch,
       data->>'target_commit_sha'                      AS target_commit_sha,
       status,
       data->>'verdict'                                AS verdict,
       COALESCE((data->>'required_reviews')::integer, 1) AS required_reviews,
       COALESCE(data->>'source', 'WORKFLOW')            AS source,
       data->>'verdict_detail'                         AS verdict_detail,
       idempotency_key,
       svc.extract_ts(data->>'completed_at') AS completed_at,
       svc.extract_ts(data->>'created_at')   AS created_at,
       svc.extract_ts(data->>'updated_at')   AS updated_at
FROM svc.review_requests;

CREATE OR REPLACE VIEW svc.v_workflow_runs AS
SELECT id,
       tenant_id,
       run_id,
       workflow_type,
       status,
       data->'input'                                   AS input,
       data->'output'                                  AS output,
       review_request_id,
       plan_id,
       (data->>'agent_id')::uuid                       AS agent_id,
       data->>'agent_name'                             AS agent_name,
       data->>'repository_id'                          AS repository_id,
       (data->>'current_execution_id')::uuid           AS current_execution_id,
       data->>'detail'                                 AS detail,
       data->>'created_by'                             AS created_by,
       parent_run_id,
       parent_step_id,
       requesting_execution_id,
       delegation_key,
       root_run_id,
       delegation_depth,
       svc.extract_ts(data->>'deadline_at') AS deadline_at,
       data->'budget'                                  AS budget,
       data->'usage_totals'                            AS usage_totals,
       data->'delegation_policy'                       AS delegation_policy,
       engine,
       svc.extract_ts(data->>'created_at')  AS created_at,
       svc.extract_ts(data->>'updated_at')  AS updated_at
FROM svc.workflow_runs;

CREATE OR REPLACE VIEW svc.v_workflow_steps AS
SELECT id,
       tenant_id,
       run_id,
       seq,
       kind,
       data->>'role'                                   AS role,
       state,
       data->'spec'                                    AS spec,
       data->'result'                                  AS result,
       (data->>'selected_agent_id')::uuid              AS selected_agent_id,
       (data->>'selected_agent_definition_version')::integer AS selected_agent_definition_version,
       current_execution_id,
       svc.extract_ts(data->>'available_at') AS available_at,
       data->'retry_policy'                            AS retry_policy,
       COALESCE((data->>'max_attempts')::integer, 3)   AS max_attempts,
       svc.extract_ts(data->>'claimed_at')   AS claimed_at,
       dispatch_attempts,
       data->>'last_error'                             AS last_error,
       review_request_id,
       review_round,
       step_key,
       data->'depends_on'                              AS depends_on,
       data->>'on_dependency_failure'                  AS on_dependency_failure,
       data->>'dispatchable_reason'                    AS dispatchable_reason,
       data->'execution_meta'                          AS execution_meta,
       svc.extract_ts(data->>'created_at')   AS created_at,
       svc.extract_ts(data->>'updated_at')   AS updated_at
FROM svc.workflow_steps;

CREATE OR REPLACE VIEW svc.v_workflow_questions AS
SELECT id,
       tenant_id,
       run_id,
       step_id,
       data->'question'                                AS question,
       routed_to,
       status,
       data->'answer'                                  AS answer,
       data->>'asked_by'                               AS asked_by,
       data->>'answered_by'                            AS answered_by,
       svc.extract_ts(data->>'created_at')  AS created_at,
       svc.extract_ts(data->>'answered_at') AS answered_at
FROM svc.workflow_questions;

CREATE OR REPLACE VIEW svc.v_registry_events AS
SELECT id,
       tenant_id,
       dedupe_key,
       data->>'aggregate_type'                         AS aggregate_type,
       (data->>'aggregate_id')::uuid                   AS aggregate_id,
       data->>'event_type'                             AS event_type,
       data->'payload'                                 AS payload,
       svc.extract_ts(data->>'claimed_at')   AS claimed_at,
       svc.extract_ts(data->>'processed_at') AS processed_at,
       process_attempts,
       data->>'last_error'                             AS last_error,
       svc.extract_ts(data->>'created_at')   AS created_at
FROM svc.registry_events;

CREATE OR REPLACE VIEW svc.v_workflow_history AS
SELECT id,
       tenant_id,
       run_id,
       (data->>'step_id')::uuid                        AS step_id,
       (data->>'plan_id')::uuid                        AS plan_id,
       (data->>'review_request_id')::uuid              AS review_request_id,
       event_type,
       data->>'actor'                                  AS actor,
       data->'detail'                                  AS detail,
       chain_seq,
       data->>'prev_digest'                            AS prev_digest,
       data->>'digest'                                 AS digest,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.workflow_history;

CREATE OR REPLACE VIEW svc.v_plan_history AS
SELECT id,
       tenant_id,
       plan_id,
       (data->>'review_request_id')::uuid              AS review_request_id,
       event_type,
       data->>'actor'                                  AS actor,
       data->>'detail'                                 AS detail,
       chain_seq,
       data->>'prev_digest'                            AS prev_digest,
       data->>'digest'                                 AS digest,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.plan_history;

CREATE OR REPLACE VIEW svc.v_executions AS
SELECT id,
       tenant_id,
       run_id,
       step_id,
       attempt_number,
       bound_agent_id,
       (data->>'agent_definition_version')::integer    AS agent_definition_version,
       data->'agent_snapshot'                          AS agent_snapshot,
       runner_id,
       preferred_runner_id,
       data->'packet'                                  AS packet,
       state,
       fencing_token,
       svc.extract_ts(data->>'lease_expires_at') AS lease_expires_at,
       svc.extract_ts(data->>'deadline_at')      AS deadline_at,
       data->'result'                                  AS result,
       data->>'settlement'                             AS settlement,
       data->>'failure_reason'                         AS failure_reason,
       (data->>'failure_retryable')::boolean           AS failure_retryable,
       svc.extract_ts(data->>'submitted_at')     AS submitted_at,
       svc.extract_ts(data->>'settled_at')       AS settled_at,
       svc.extract_ts(data->>'last_heartbeat_at') AS last_heartbeat_at,
       data->'progress'                                AS progress,
       progress_sampled_at_ms,
       svc.extract_ts(data->>'last_observed_progress_at') AS last_observed_progress_at,
       svc.extract_ts(data->>'created_at')       AS created_at,
       svc.extract_ts(data->>'updated_at')       AS updated_at
FROM svc.executions;

CREATE OR REPLACE VIEW svc.v_execution_artifacts AS
SELECT id,
       tenant_id,
       execution_id,
       kind,
       digest,
       data->'content'                                 AS content,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.execution_artifacts;

CREATE OR REPLACE VIEW svc.v_runner_identity AS
SELECT id,
       tenant_id,
       runner_id,
       data->>'public_key'                             AS public_key,
       svc.extract_ts(data->>'created_at') AS created_at,
       svc.extract_ts(data->>'updated_at') AS updated_at
FROM svc.runner_identity;

CREATE OR REPLACE VIEW svc.v_runner_view AS
SELECT id,
       tenant_id,
       runner_id,
       status,
       data->'supported_launchers'                     AS supported_launchers,
       data->>'sandbox'                                AS sandbox,
       data->'repositories'                            AS repositories,
       session_open,
       svc.extract_ts(data->>'observed_at') AS observed_at,
       data->>'open_session_id'                        AS open_session_id
FROM svc.runner_view;

CREATE OR REPLACE VIEW svc.v_agent_view AS
SELECT id,
       tenant_id,
       agent_id,
       data->>'launcher'                               AS launcher,
       status,
       data->'allowed_runners'                         AS allowed_runners,
       svc.extract_ts(data->>'observed_at') AS observed_at
FROM svc.agent_view;

CREATE OR REPLACE VIEW svc.v_execution_event AS
SELECT id,
       tenant_id,
       execution_id,
       seq,
       data->>'kind'                                   AS kind,
       data->'payload'                                 AS payload,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.execution_event;

CREATE OR REPLACE VIEW svc.v_reasoning_decision AS
SELECT id,
       tenant_id,
       run_id,
       step_id,
       attempt_number,
       idempotency_key,
       data->>'request_id'                             AS request_id,
       data->>'status'                                 AS status,
       data->>'action'                                 AS action,
       data->>'impact'                                 AS impact,
       data->>'action_reason'                          AS action_reason,
       data->>'model_id'                               AS model_id,
       data->>'model_digest'                           AS model_digest,
       data->>'model_provider'                         AS model_provider,
       data->>'reasoning_route'                        AS reasoning_route,
       (data->>'fallback_used')::boolean               AS fallback_used,
       data->>'runtime_backend'                        AS runtime_backend,
       data->>'runtime_version'                        AS runtime_version,
       data->>'prompt_id'                              AS prompt_id,
       (data->>'prompt_version')::integer              AS prompt_version,
       data->>'prompt_hash'                            AS prompt_hash,
       (data->>'wrapper_version')::integer             AS wrapper_version,
       data->>'data_hash'                              AS data_hash,
       data->>'context_ref'                            AS context_ref,
       (data->>'temperature')::float8                  AS temperature,
       (data->>'seed')::integer                        AS seed,
       (data->>'top_p')::float8                        AS top_p,
       (data->>'max_output_tokens')::integer           AS max_output_tokens,
       data->>'finish_reason'                          AS finish_reason,
       (data->>'schema_valid')::boolean                AS schema_valid,
       data->'proposal'                                AS proposal,
       data->'validations'                             AS validations,
       data->>'human_confirmation'                     AS human_confirmation,
       data->>'final_decision'                         AS final_decision,
       data->>'failure_reason'                         AS failure_reason,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.reasoning_decision;

CREATE OR REPLACE VIEW svc.v_conversations AS
SELECT id,
       tenant_id,
       data->>'title'                                  AS title,
       status,
       data->>'created_by'                             AS created_by,
       svc.extract_ts(data->>'created_at') AS created_at,
       svc.extract_ts(data->>'updated_at') AS updated_at
FROM svc.conversations;

CREATE OR REPLACE VIEW svc.v_messages AS
SELECT id,
       tenant_id,
       conversation_id,
       seq,
       sender_type,
       data->>'sender_id'                              AS sender_id,
       (data->>'sender_agent_id')::uuid                AS sender_agent_id,
       data->>'recipient_type'                         AS recipient_type,
       data->>'recipient_id'                           AS recipient_id,
       reply_to_message_id,
       (data->>'correlation_id')::uuid                 AS correlation_id,
       COALESCE(data->>'message_type', 'AGENT_MESSAGE') AS message_type,
       data->>'instructions'                           AS instructions,
       data->'context'                                 AS context,
       data->'resource_refs'                           AS resource_refs,
       run_id,
       status,
       idempotency_key,
       data->'sender_meta'                             AS sender_meta,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.messages;

CREATE OR REPLACE VIEW svc.v_tenant_policy AS
SELECT tenant_id,
       (data->>'max_concurrent_executions')::integer   AS max_concurrent_executions,
       (data->>'max_steps_per_tree')::integer          AS max_steps_per_tree,
       (data->>'max_total_attempts')::integer          AS max_total_attempts,
       (data->>'max_child_runs')::integer              AS max_child_runs,
       (data->>'max_delegation_depth')::integer        AS max_delegation_depth,
       (data->>'default_deadline_seconds')::bigint     AS default_deadline_seconds,
       data->'usage_caps'                              AS usage_caps,
       data->'delegation'                              AS delegation,
       COALESCE((data->>'session_reuse_enabled')::boolean, true) AS session_reuse_enabled,
       svc.extract_ts(data->>'updated_at') AS updated_at
FROM svc.tenant_policy;

CREATE OR REPLACE VIEW svc.v_budget_ledger AS
SELECT tenant_id,
       root_run_id,
       steps_created,
       attempts_created,
       children_created,
       COALESCE(data->'usage', '{}'::jsonb)            AS usage,
       svc.extract_ts(data->>'updated_at') AS updated_at
FROM svc.budget_ledger;

CREATE OR REPLACE VIEW svc.v_plan_repositories AS
SELECT id,
       tenant_id,
       plan_id,
       ordinal,
       repository_id,
       data->>'remote_url'                             AS remote_url,
       data->>'branch'                                 AS branch,
       data->>'commit_sha'                             AS commit_sha,
       svc.extract_ts(data->>'created_at') AS created_at
FROM svc.plan_repositories;

CREATE OR REPLACE VIEW svc.v_workflow_transitions AS
SELECT id,
       tenant_id,
       run_id,
       seq,
       transition_key,
       data->>'direction'                              AS direction,
       data->'payload'                                 AS payload,
       data->>'payload_hash'                           AS payload_hash,
       state,
       claim_fence,
       svc.extract_ts(data->>'claim_expires_at') AS claim_expires_at,
       attempts,
       data->>'last_error'                             AS last_error,
       data->'response_payload'                        AS response_payload,
       svc.extract_ts(data->>'created_at') AS created_at,
       svc.extract_ts(data->>'updated_at') AS updated_at
FROM svc.workflow_transitions;

CREATE OR REPLACE VIEW svc.v_provider_sessions AS
SELECT id,
       tenant_id,
       session_id,
       provider,
       data->>'provider_session_id'                    AS provider_session_id,
       scope_type,
       scope_id,
       scope_sub_id,
       data->>'workspace_identity'                     AS workspace_identity,
       data->>'repository_id'                          AS repository_id,
       data->>'base_commit_sha'                        AS base_commit_sha,
       data->>'current_commit_sha'                     AS current_commit_sha,
       fencing_token,
       status,
       runner_id,
       lease_execution_id,
       svc.extract_ts(data->>'leased_at')        AS leased_at,
       svc.extract_ts(data->>'lease_expires_at') AS lease_expires_at,
       last_scope_seq,
       data->'metadata'                                AS metadata,
       svc.extract_ts(data->>'created_at')       AS created_at,
       svc.extract_ts(data->>'updated_at')       AS updated_at
FROM svc.provider_sessions;
