SELECT pg_advisory_lock(hashtext('w001.fixture.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w529(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE TABLE IF NOT EXISTS fixture.w521 (
    w027            uuid PRIMARY KEY DEFAULT uuidv7(),
    w026     uuid NOT NULL,
    w530 text NOT NULL,
    w185      integer NOT NULL DEFAULT 1,
    w184       boolean NOT NULL DEFAULT false,
    data          jsonb NOT NULL,
    CONSTRAINT w531 UNIQUE (w026, w027)
);

CREATE INDEX IF NOT EXISTS w532
    ON fixture.w521 (w026, w530);

CREATE TABLE IF NOT EXISTS fixture.w533 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w534 uuid NOT NULL,
    w185    integer NOT NULL,
    data        jsonb NOT NULL,
    CONSTRAINT w535 FOREIGN KEY (w026, w534)
        REFERENCES fixture.w521 (w026, w027),
    CONSTRAINT w536 UNIQUE (w026, w534, w185)
);

CREATE INDEX IF NOT EXISTS w537
    ON fixture.w533 (w026, w534);

CREATE TABLE IF NOT EXISTS fixture.w298 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL,
    w074    text NOT NULL,
    data      jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w299
    ON fixture.w298 (w026) WHERE (w074 = 'W300');
CREATE INDEX IF NOT EXISTS w301
    ON fixture.w298 (w026, (fixture.w529(data->>'w302')) DESC);

CREATE OR REPLACE VIEW fixture.w538 AS
SELECT w027,
       w026,
       w530,
       w185,
       w184,
       data->>'w539'                                 AS w539,
       (data->>'w540')::bigint                               AS w540,
       data->>'digest'                                       AS digest,
       data->>'w541'                            AS w541,
       data->>'w542'                            AS w542,
       data->>'name'                                         AS name,
       data->>'w307'                                   AS w307,
       fixture.w529(data->>'w090')     AS w090,
       fixture.w529(data->>'w161')     AS w161
FROM fixture.w521;

CREATE OR REPLACE VIEW fixture.w543 AS
SELECT w027,
       w026,
       w534,
       w185,
       data->>'w539'                                 AS w539,
       (data->>'w540')::bigint                               AS w540,
       data->>'digest'                                       AS digest,
       data->>'w541'                            AS w541,
       data->>'w542'                            AS w542,
       data->>'w307'                                   AS w307,
       fixture.w529(data->>'w090')     AS w090
FROM fixture.w533;

CREATE OR REPLACE VIEW fixture.w350 AS
SELECT w027                                                       AS w544,
       w026,
       fixture.w529(data->>'w302')     AS w302,
       fixture.w529(data->>'w337')     AS w337,
       w074,
       data->>'w344'                                          AS w344
FROM fixture.w298;

SELECT pg_advisory_unlock(hashtext('w001.fixture.schema'));
