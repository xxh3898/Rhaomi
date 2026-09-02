CREATE TABLE publish_generation_state (
    singleton_key SMALLINT NOT NULL,
    publish_generation BIGINT NOT NULL,
    CONSTRAINT pk_publish_generation_state PRIMARY KEY (singleton_key),
    CONSTRAINT ck_publish_generation_state_singleton CHECK (singleton_key = 1),
    CONSTRAINT ck_publish_generation_state_generation CHECK (publish_generation >= 0)
);

INSERT INTO publish_generation_state (singleton_key, publish_generation)
VALUES (1, 0);

ALTER TABLE publishing_outbox
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN publish_generation BIGINT,
    ADD COLUMN attempt_count SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN claim_owner VARCHAR(128),
    ADD COLUMN claimed_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN lease_until TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN last_result_code VARCHAR(32),
    ADD COLUMN coalesced_into_generation BIGINT;

ALTER TABLE publishing_outbox
    ADD CONSTRAINT uk_publishing_outbox_publish_generation UNIQUE (publish_generation),
    ADD CONSTRAINT ck_publishing_outbox_state CHECK (
        state IN (
            'PENDING',
            'PROCESSING',
            'RETRY_WAIT',
            'SUCCEEDED',
            'NOOP',
            'FAILED',
            'COALESCED'
        )
    ),
    ADD CONSTRAINT ck_publishing_outbox_publish_generation CHECK (
        publish_generation IS NULL OR publish_generation > 0
    ),
    ADD CONSTRAINT ck_publishing_outbox_attempt_count CHECK (
        attempt_count BETWEEN 0 AND 4
    ),
    ADD CONSTRAINT ck_publishing_outbox_claim_owner CHECK (
        claim_owner IS NULL
        OR (
            char_length(claim_owner) BETWEEN 1 AND 128
            AND claim_owner = btrim(claim_owner)
            AND claim_owner ~ '[^[:space:]]'
            AND claim_owner !~ '[[:cntrl:]]'
        )
    ),
    ADD CONSTRAINT ck_publishing_outbox_result_code CHECK (
        last_result_code IS NULL
        OR last_result_code IN (
            'SUCCESS',
            'STALE_TRIGGER',
            'NO_PUBLIC_CHANGE',
            'TRANSIENT_FAILURE',
            'RETRY_EXHAUSTED',
            'TERMINAL_FAILURE',
            'COALESCED',
            'LEASE_EXPIRED'
        )
    ),
    ADD CONSTRAINT ck_publishing_outbox_coalesced_generation CHECK (
        coalesced_into_generation IS NULL OR coalesced_into_generation > 0
    ),
    ADD CONSTRAINT ck_publishing_outbox_state_shape CHECK (
        (
            state = 'PENDING'
            AND publish_generation IS NULL
            AND attempt_count = 0
            AND claim_owner IS NULL
            AND claimed_at IS NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NULL
            AND last_result_code IS NULL
            AND coalesced_into_generation IS NULL
        )
        OR (
            state = 'PROCESSING'
            AND publish_generation IS NOT NULL
            AND attempt_count BETWEEN 1 AND 4
            AND claim_owner IS NOT NULL
            AND claimed_at IS NOT NULL
            AND lease_until IS NOT NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NULL
            AND lease_until > claimed_at
            AND (
                last_result_code IS NULL
                OR last_result_code IN ('TRANSIENT_FAILURE', 'LEASE_EXPIRED')
            )
            AND coalesced_into_generation IS NULL
        )
        OR (
            state = 'RETRY_WAIT'
            AND publish_generation IS NOT NULL
            AND attempt_count BETWEEN 1 AND 3
            AND claim_owner IS NULL
            AND claimed_at IS NOT NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NOT NULL
            AND next_attempt_at > claimed_at
            AND completed_at IS NULL
            AND last_result_code = 'TRANSIENT_FAILURE'
            AND coalesced_into_generation IS NULL
        )
        OR (
            state = 'SUCCEEDED'
            AND publish_generation IS NOT NULL
            AND attempt_count BETWEEN 1 AND 4
            AND claim_owner IS NULL
            AND claimed_at IS NOT NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL
            AND completed_at >= claimed_at
            AND last_result_code = 'SUCCESS'
            AND coalesced_into_generation IS NULL
        )
        OR (
            state = 'NOOP'
            AND claim_owner IS NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL
            AND coalesced_into_generation IS NULL
            AND (
                (
                    publish_generation IS NULL
                    AND attempt_count = 0
                    AND claimed_at IS NULL
                    AND last_result_code = 'STALE_TRIGGER'
                )
                OR (
                    publish_generation IS NOT NULL
                    AND attempt_count BETWEEN 1 AND 4
                    AND claimed_at IS NOT NULL
                    AND completed_at >= claimed_at
                    AND last_result_code IN ('STALE_TRIGGER', 'NO_PUBLIC_CHANGE')
                )
            )
        )
        OR (
            state = 'FAILED'
            AND publish_generation IS NOT NULL
            AND attempt_count BETWEEN 1 AND 4
            AND claim_owner IS NULL
            AND claimed_at IS NOT NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL
            AND completed_at >= claimed_at
            AND last_result_code IN ('RETRY_EXHAUSTED', 'TERMINAL_FAILURE')
            AND (
                last_result_code <> 'RETRY_EXHAUSTED'
                OR attempt_count = 4
            )
            AND coalesced_into_generation IS NULL
        )
        OR (
            state = 'COALESCED'
            AND publish_generation IS NOT NULL
            AND attempt_count BETWEEN 1 AND 4
            AND claim_owner IS NULL
            AND claimed_at IS NOT NULL
            AND lease_until IS NULL
            AND next_attempt_at IS NULL
            AND completed_at IS NOT NULL
            AND completed_at >= claimed_at
            AND last_result_code = 'COALESCED'
            AND coalesced_into_generation IS NOT NULL
            AND coalesced_into_generation > publish_generation
        )
    );

ALTER TABLE publishing_outbox
    ADD CONSTRAINT fk_publishing_outbox_coalesced_generation
        FOREIGN KEY (coalesced_into_generation)
        REFERENCES publishing_outbox (publish_generation)
        ON UPDATE RESTRICT
        ON DELETE RESTRICT;

CREATE INDEX ix_publishing_outbox_state_available
    ON publishing_outbox (state, available_at, id);

CREATE INDEX ix_publishing_outbox_state_next_attempt
    ON publishing_outbox (state, next_attempt_at, id);

CREATE INDEX ix_publishing_outbox_state_lease
    ON publishing_outbox (state, lease_until, id);

CREATE INDEX ix_publishing_outbox_coalesced_generation
    ON publishing_outbox (coalesced_into_generation);
