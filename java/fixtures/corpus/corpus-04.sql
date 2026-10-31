SELECT pg_advisory_lock(hashtext('w001.fixture.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w459(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE TABLE IF NOT EXISTS fixture.w460 (
    w027                 uuid PRIMARY KEY DEFAULT uuidv7(),
    w461             text NOT NULL,
    w074             text NOT NULL DEFAULT 'OPEN',
    w462 integer NOT NULL DEFAULT 0,
    data               jsonb NOT NULL,
    CONSTRAINT w463 UNIQUE (w461)
);

CREATE INDEX IF NOT EXISTS w464
    ON fixture.w460 ((fixture.w459(data->>'w440'))) WHERE w074 = 'OPEN';
CREATE INDEX IF NOT EXISTS w465
    ON fixture.w460 ((fixture.w459(data->>'w466'))) WHERE w074 = 'W184';
CREATE INDEX IF NOT EXISTS w467
    ON fixture.w460 ((fixture.w459(data->>'w468'))) WHERE w074 = 'W469';

CREATE TABLE IF NOT EXISTS fixture.w470 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w461          text NOT NULL,
    w026       uuid NOT NULL,
    w146 text NOT NULL,
    data            jsonb NOT NULL,
    CONSTRAINT w471 FOREIGN KEY (w461)
        REFERENCES fixture.w460 (w461),
    CONSTRAINT w472 UNIQUE (w461, w026),
    CONSTRAINT w473 UNIQUE (w461, w146)
);

CREATE INDEX IF NOT EXISTS w474 ON fixture.w470 (w461);

CREATE TABLE IF NOT EXISTS fixture.w475 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w461     text NOT NULL,
    w026  uuid NOT NULL,
    w476 uuid NOT NULL,
    w074     text NOT NULL DEFAULT 'W477',
    data       jsonb NOT NULL,
    CONSTRAINT w478 FOREIGN KEY (w461, w026)
        REFERENCES fixture.w470 (w461, w026),
    CONSTRAINT w479 UNIQUE (w461, w026, w476),
    CONSTRAINT w480 CHECK (
        data->>'w481' IS NULL
        OR data->>'w481' ~ '^[0-9a-f]{64}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS w482
    ON fixture.w475 (w026) WHERE w074 = 'W477';
CREATE INDEX IF NOT EXISTS w483
    ON fixture.w475 (w026, (fixture.w459(data->>'w302')) DESC);

CREATE TABLE IF NOT EXISTS fixture.w484 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w461    text NOT NULL,
    w026 uuid,
    action    text NOT NULL,
    data      jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w485
    ON fixture.w484 (w461, (fixture.w459(data->>'w371')));
CREATE INDEX IF NOT EXISTS w486
    ON fixture.w484 ((fixture.w459(data->>'w371')));

CREATE TABLE IF NOT EXISTS fixture.w487 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w461    text NOT NULL,
    w074    text NOT NULL DEFAULT 'W488',
    w489  text,
    w490 jsonb NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w491 FOREIGN KEY (w461)
        REFERENCES fixture.w460 (w461)
);

CREATE INDEX IF NOT EXISTS w492
    ON fixture.w487 (w461, (fixture.w459(data->>'w090')));
CREATE INDEX IF NOT EXISTS w493
    ON fixture.w487 ((fixture.w459(data->>'w090'))) WHERE w074 = 'W488';
CREATE INDEX IF NOT EXISTS w494
    ON fixture.w487 ((fixture.w459(data->>'w495'))) WHERE w074 = 'W496';

CREATE TABLE IF NOT EXISTS fixture.w497 (
    w027           uuid PRIMARY KEY DEFAULT uuidv7(),
    w379 uuid NOT NULL,
    w498      text NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT w499 FOREIGN KEY (w379)
        REFERENCES fixture.w487 (w027),
    CONSTRAINT w500 UNIQUE (w379, w498)
);

CREATE OR REPLACE VIEW fixture.w501 AS
SELECT w027,
       w461,
       w074,
       w462,
       data->>'w502'                       AS w502,
       fixture.w459(data->>'w440')   AS w440,
       fixture.w459(data->>'w090')   AS w090,
       fixture.w459(data->>'w468')    AS w468,
       fixture.w459(data->>'w318')   AS w318,
       fixture.w459(data->>'w466')  AS w466
FROM fixture.w460;

CREATE OR REPLACE VIEW fixture.w503 AS
SELECT w027,
       w461,
       w026,
       w146,
       (data->>'w504')::uuid      AS w504,
       fixture.w459(data->>'w090')   AS w090
FROM fixture.w470;

CREATE OR REPLACE VIEW fixture.w505 AS
SELECT w027,
       w461,
       w026,
       w476,
       w074,
       data->'w506'                           AS w506,
       data->>'w481'                   AS w481,
       data->>'w344'                             AS w344,
       fixture.w459(data->>'w302') AS w302,
       fixture.w459(data->>'w337') AS w337
FROM fixture.w475;

CREATE OR REPLACE VIEW fixture.w507 AS
SELECT w027,
       w461,
       w026,
       action,
       data->>'w508'                             AS w508,
       data->'detail'                             AS detail,
       fixture.w459(data->>'w371') AS w371
FROM fixture.w484;

CREATE OR REPLACE VIEW fixture.w509 AS
SELECT w027,
       w461,
       w490,
       w074,
       w489,
       data->>'w510'                          AS w510,
       (data->>'w511')::int                  AS w511,
       data->>'w344'                             AS w344,
       fixture.w459(data->>'w495') AS w495,
       fixture.w459(data->>'w090')   AS w090,
       fixture.w459(data->>'w512')   AS w512,
       fixture.w459(data->>'w513')  AS w513
FROM fixture.w487;

CREATE OR REPLACE VIEW fixture.w514 AS
SELECT w027,
       w379,
       w498,
       data->>'w515'                              AS w515,
       data->>'w516'                             AS w516,
       data->>'w517'                           AS w517,
       (data->>'w518')::int                AS w518,
       data->>'w136'                            AS w136,
       data->'w519'                            AS w519,
       data->'w520'                              AS w520,
       data->'w521'                          AS w521,
       fixture.w459(data->>'w387') AS w387
FROM fixture.w497;

SELECT pg_advisory_unlock(hashtext('w001.fixture.schema'));
