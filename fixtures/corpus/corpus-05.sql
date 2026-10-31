CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w522(t text) RETURNS timestamptz
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN t IS NULL OR t = '' THEN NULL ELSE t::timestamptz END
$$;

CREATE TABLE IF NOT EXISTS fixture.w523 (
    w027            UUID PRIMARY KEY DEFAULT uuidv7(),
    w026     UUID NOT NULL,
    w411  TEXT NOT NULL,
    w424 UUID NOT NULL,
    data          JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS w524
    ON fixture.w523 ((fixture.w522(data->>'w440')));

CREATE OR REPLACE VIEW fixture.w525 AS
SELECT s.w027,
       s.w026,
       s.w411,
       s.w424,
       s.data->'w445'                                  AS w445,
       s.data->>'w526'                         AS w526,
       fixture.w522(s.data->>'w090')   AS w090,
       fixture.w522(s.data->>'w208') AS w208,
       fixture.w522(s.data->>'w440')   AS w440,
       fixture.w522(s.data->>'w527') AS w527,
       s.data->>'w528'                              AS w528
FROM fixture.w523 s;
