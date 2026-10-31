SELECT pg_advisory_lock(hashtext('w001.fixture.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w408(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE TABLE IF NOT EXISTS fixture.w409 (
    w027                    uuid PRIMARY KEY DEFAULT uuidv7(),
    w026             uuid NOT NULL,
    w410        text NOT NULL,
    w411          text NOT NULL,
    w412          text NOT NULL,
    w074                text NOT NULL,
    w413  uuid REFERENCES fixture.w409 (w027),
    data                  jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w414
    ON fixture.w409 (w412) WHERE w074 = 'W415';

CREATE UNIQUE INDEX IF NOT EXISTS w416
    ON fixture.w409 (w026, w411)
    WHERE w410 = 'W417' AND w074 = 'W415';
CREATE INDEX IF NOT EXISTS w418
    ON fixture.w409 (w413);
CREATE INDEX IF NOT EXISTS w419
    ON fixture.w409 (w026, w074);
CREATE INDEX IF NOT EXISTS w420
    ON fixture.w409 ((fixture.w408(data->>'w090')));
CREATE INDEX IF NOT EXISTS w421
    ON fixture.w409 (w026) WHERE (data->>'w422')::boolean;

CREATE TABLE IF NOT EXISTS fixture.w423 (
    w027                  uuid PRIMARY KEY DEFAULT uuidv7(),
    w026           uuid NOT NULL,
    w424       uuid NOT NULL REFERENCES fixture.w409 (w027),
    w425 uuid REFERENCES fixture.w409 (w027),
    data                jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w426
    ON fixture.w423 (w026, w424);
CREATE INDEX IF NOT EXISTS w427
    ON fixture.w423 ((fixture.w408(data->>'w371')));

CREATE TABLE IF NOT EXISTS fixture.w428 (
    w027   uuid PRIMARY KEY DEFAULT uuidv7(),
    w429  text NOT NULL,
    data jsonb NOT NULL,
    CONSTRAINT w430 UNIQUE (w429)
);

CREATE INDEX IF NOT EXISTS w431
    ON fixture.w428 (w429) WHERE (data->>'w346') IS NULL;

CREATE TABLE IF NOT EXISTS fixture.w432 (
    w027                     uuid PRIMARY KEY DEFAULT uuidv7(),
    w026              uuid NOT NULL,
    w433             text NOT NULL,
    w434 uuid REFERENCES fixture.w409 (w027),
    data                   jsonb NOT NULL,
    CONSTRAINT w435 UNIQUE (w433)
);

CREATE INDEX IF NOT EXISTS w436 ON fixture.w432 (w026);

CREATE TABLE IF NOT EXISTS fixture.w437 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w433 text NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT w438 UNIQUE (w433)
);

CREATE INDEX IF NOT EXISTS w439
    ON fixture.w437 ((fixture.w408(data->>'w440')));

CREATE TABLE IF NOT EXISTS fixture.w441 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w030     text NOT NULL,
    w442 text NOT NULL,
    day       date NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w443 UNIQUE (w030, w442, day)
);

CREATE OR REPLACE VIEW fixture.w444 AS
SELECT w027,
       w026,
       w410,
       w411,
       (SELECT COALESCE(array_agg(s), '{}'::text[])
        FROM jsonb_array_elements_text(data->'w445') AS s) AS w445,
       w074,
       w413,
       w412,
       data->>'w446'                      AS w446,
       (data->>'w446') IS NOT NULL        AS w447,
       (data->>'w448')::boolean                   AS w448,
       (data->>'w422')::boolean                      AS w422,
       fixture.w408(data->>'w440')        AS w440,
       fixture.w408(data->>'w090')        AS w090,
       fixture.w408(data->>'w161')        AS w161
FROM fixture.w409;

CREATE OR REPLACE VIEW fixture.w449 AS
SELECT w027,
       w026,
       w424,
       data->>'action'                            AS action,
       w425,
       fixture.w408(data->>'w371') AS w371
FROM fixture.w423;

CREATE OR REPLACE VIEW fixture.w450 AS
SELECT w027,
       w429,
       data->>'w451'             AS w451,
       data->>'w452'                        AS w452,
       fixture.w408(data->>'w090')  AS w090,
       fixture.w408(data->>'w346')  AS w346
FROM fixture.w428;

CREATE OR REPLACE VIEW fixture.w453 AS
SELECT w027,
       w026,
       w433,
       data->>'w074'                           AS w074,
       w434,
       fixture.w408(data->>'w440') AS w440,
       fixture.w408(data->>'w090') AS w090
FROM fixture.w432;

CREATE OR REPLACE VIEW fixture.w454 AS
SELECT w027,
       w433,
       data->>'w455'                          AS w455,
       (data->>'w456')::int                AS w456,
       fixture.w408(data->>'w440') AS w440,
       fixture.w408(data->>'w457')    AS w457,
       fixture.w408(data->>'w090') AS w090
FROM fixture.w437;

CREATE OR REPLACE VIEW fixture.w458 AS
SELECT w027,
       w030,
       w442,
       day,
       (data->>'count')::int AS count
FROM fixture.w441;

SELECT pg_advisory_unlock(hashtext('w001.fixture.schema'));
