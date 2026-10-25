SELECT pg_advisory_lock(hashtext('w001.fixture.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w351(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE TABLE IF NOT EXISTS fixture.w145 (
    w027       uuid PRIMARY KEY DEFAULT uuidv7(),
    w175 uuid NOT NULL,
    data     jsonb NOT NULL,
    CONSTRAINT w352 UNIQUE (w175)
);

CREATE TABLE IF NOT EXISTS fixture.w353 (
    w027       uuid PRIMARY KEY DEFAULT uuidv7(),
    w175 uuid NOT NULL,
    data     jsonb NOT NULL,
    CONSTRAINT w354 UNIQUE (w175)
);

CREATE TABLE IF NOT EXISTS fixture.w355 (
    w027       uuid PRIMARY KEY DEFAULT uuidv7(),
    w175 uuid,
    data     jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS fixture.w356 (
    w027   uuid PRIMARY KEY DEFAULT uuidv7(),
    data jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS fixture.w357 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 text NOT NULL,
    w358 text NOT NULL,
    w359    timestamptz NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w360 UNIQUE (w026, w358, w359)
);

CREATE TABLE IF NOT EXISTS fixture.w361 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 text NOT NULL,
    w358 text NOT NULL,
    w359    date NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w362 UNIQUE (w026, w358, w359)
);

CREATE TABLE IF NOT EXISTS fixture.w363 (
    w027                uuid PRIMARY KEY DEFAULT uuidv7(),
    w026         text NOT NULL,
    w364           text NOT NULL,
    w358         text NOT NULL,
    w359            timestamptz NOT NULL,
    w365 text NOT NULL,
    data              jsonb NOT NULL,
    CONSTRAINT w366 UNIQUE (w026, w364, w358, w359, w365)
);

CREATE TABLE IF NOT EXISTS fixture.w367 (
    w027   uuid PRIMARY KEY DEFAULT uuidv7(),
    data jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS fixture.w368 (
    w027   uuid PRIMARY KEY DEFAULT uuidv7(),
    key  text NOT NULL,
    data jsonb NOT NULL,
    CONSTRAINT w369 UNIQUE (key)
);

CREATE INDEX IF NOT EXISTS w370
    ON fixture.w145 ((data->>'w026'), (fixture.w351(data->>'w371')));
CREATE INDEX IF NOT EXISTS w372
    ON fixture.w145 ((data->>'w358'), (fixture.w351(data->>'w371')));
CREATE INDEX IF NOT EXISTS w373
    ON fixture.w145 ((data->>'w364'), (fixture.w351(data->>'w371')));
CREATE INDEX IF NOT EXISTS w374
    ON fixture.w145 ((fixture.w351(data->>'w375')));
CREATE INDEX IF NOT EXISTS w376
    ON fixture.w145 ((data->>'w377')) WHERE (data->>'w377') IS NOT NULL;
CREATE INDEX IF NOT EXISTS w378
    ON fixture.w145 ((data->>'w379')) WHERE (data->>'w379') IS NOT NULL;
CREATE INDEX IF NOT EXISTS w380
    ON fixture.w145 ((data->>'w381')) WHERE (data->>'w381') IS NOT NULL;

CREATE INDEX IF NOT EXISTS w382
    ON fixture.w353 ((data->>'w026'), (fixture.w351(data->>'w371')));
CREATE INDEX IF NOT EXISTS w383
    ON fixture.w353 ((data->>'w358'), (fixture.w351(data->>'w371')));

CREATE INDEX IF NOT EXISTS w384
    ON fixture.w355 ((fixture.w351(data->>'w375')));

CREATE INDEX IF NOT EXISTS w385
    ON fixture.w356 ((data->>'w026'), (data->>'w358'),
                            (fixture.w351(data->>'w371')));

CREATE INDEX IF NOT EXISTS w386
    ON fixture.w367 ((data->>'w364'), (data->>'w358'),
                        (fixture.w351(data->>'w387')) DESC);

CREATE OR REPLACE VIEW fixture.w303 AS
SELECT w027,
       w175,
       (data->>'w388')::int        AS w388,
       fixture.w351(data->>'w371') AS w371,
       fixture.w351(data->>'w375') AS w375,
       data->>'w026'                    AS w026,
       data->>'w379'                 AS w379,
       data->>'w377'                  AS w377,
       data->>'w381'                     AS w381,
       data->>'w364'                      AS w364,
       data->>'w358'                    AS w358,
       (data->>'w389')::numeric          AS w389,
       data->>'w390'                         AS w390,
       data->>'w073'                       AS w073,
       data->>'w341'                   AS w341,
       data->'w081'                      AS w081,
       data - 'w375'                  AS w271
FROM fixture.w145;

CREATE OR REPLACE VIEW fixture.w391 AS
SELECT w027,
       w175,
       (data->>'w388')::int        AS w388,
       fixture.w351(data->>'w371') AS w371,
       fixture.w351(data->>'w375') AS w375,
       data->>'w026'                    AS w026,
       data->>'w379'                 AS w379,
       data->>'w377'                  AS w377,
       data->>'w381'                     AS w381,
       data->>'w364'                      AS w364,
       data->>'w358'                    AS w358,
       (data->>'w389')::numeric          AS w389,
       data->>'w390'                         AS w390,
       data->>'w073'                       AS w073,
       data->>'w341'                   AS w341,
       data->'w081'                      AS w081,
       data - 'w375' - 'w392'  AS w271,
       fixture.w351(data->>'w392') AS w392
FROM fixture.w353;

CREATE OR REPLACE VIEW fixture.w393 AS
SELECT w027,
       w175,
       fixture.w351(data->>'w375') AS w375,
       data->>'w136'   AS w136,
       data->>'detail'   AS detail,
       data->'w394'  AS w394,
       data->'stored'    AS stored,
       data->>'w364'  AS w364
FROM fixture.w355;

CREATE OR REPLACE VIEW fixture.w395 AS
SELECT w027,
       fixture.w351(data->>'w371') AS w371,
       fixture.w351(data->>'w375') AS w375,
       data->>'w026'  AS w026,
       data->>'w358'  AS w358,
       (data->>'w389')::numeric AS w389,
       data->>'w390'       AS w390,
       data->'w081'    AS w081
FROM fixture.w356;

CREATE OR REPLACE VIEW fixture.w396 AS
SELECT w027, w026, w358, w359,
       (data->>'w389')::numeric     AS w389,
       (data->>'w397')::bigint   AS w397
FROM fixture.w357;

CREATE OR REPLACE VIEW fixture.w398 AS
SELECT w027, w026, w358, w359,
       (data->>'w389')::numeric     AS w389,
       (data->>'w397')::bigint   AS w397
FROM fixture.w361;

CREATE OR REPLACE VIEW fixture.w399 AS
SELECT w027, w026, w364, w358, w359, w365,
       (data->>'w400')::numeric AS w400,
       fixture.w351(data->>'w401')  AS w401
FROM fixture.w363;

CREATE OR REPLACE VIEW fixture.w402 AS
SELECT w027,
       data->>'w364'     AS w364,
       data->>'w358'   AS w358,
       data->>'w390'        AS w390,
       data->>'w403' AS w403,
       (data->>'w404')::numeric AS w404,
       fixture.w351(data->>'w387') AS w387
FROM fixture.w367;

CREATE OR REPLACE VIEW fixture.w405 AS
SELECT w027, key,
       fixture.w351(data->>'w406') AS w406,
       data->>'w407' AS w407
FROM fixture.w368;

SELECT pg_advisory_unlock(hashtext('w001.fixture.schema'));
