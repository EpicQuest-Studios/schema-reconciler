SELECT pg_advisory_lock(hashtext('w001.fixture.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w727(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE TABLE IF NOT EXISTS fixture.w728 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w292  uuid NOT NULL,
    w729 text NOT NULL,
    w730   uuid NOT NULL,
    w128       text NOT NULL DEFAULT 'DEFERRED',
    data        jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w731
    ON fixture.w728 (w026, w292, w729, w730);
CREATE INDEX IF NOT EXISTS w732
    ON fixture.w728 (w026, w292);
CREATE INDEX IF NOT EXISTS w733
    ON fixture.w728 ((fixture.w727(data->>'w734')));

CREATE TABLE IF NOT EXISTS fixture.w735 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL,
    w292 uuid NOT NULL,
    data       jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w736
    ON fixture.w735 (w026, w292);
CREATE INDEX IF NOT EXISTS w737
    ON fixture.w735 ((fixture.w727(data->>'w090')));
CREATE INDEX IF NOT EXISTS w738
    ON fixture.w735 (w026, w292, (fixture.w727(data->>'w090')) DESC, w027 DESC);

CREATE TABLE IF NOT EXISTS fixture.w094 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w293 uuid NOT NULL CONSTRAINT w739
        REFERENCES fixture.w735 (w027),
    w292  uuid NOT NULL,
    w729 text NOT NULL,
    w730   uuid NOT NULL,
    action      text NOT NULL,
    data        jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w740
    ON fixture.w094 (w293);
CREATE INDEX IF NOT EXISTS w741
    ON fixture.w094 (w292);

CREATE TABLE IF NOT EXISTS fixture.w742 (
    w026  uuid NOT NULL,
    w292 uuid NOT NULL,
    w175   uuid NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT w743 PRIMARY KEY (w026, w292, w175)
);

CREATE INDEX IF NOT EXISTS w744
    ON fixture.w742 (w026, w175);

CREATE TABLE IF NOT EXISTS fixture.w745 (
    w027                     uuid PRIMARY KEY DEFAULT uuidv7(),
    w746         text NOT NULL,
    w747        text NOT NULL,
    w748         text NOT NULL,
    w749 text NOT NULL,
    w750  text NOT NULL,
    w751              boolean NOT NULL DEFAULT false,
    w752            text NOT NULL,
    data                   jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w753
    ON fixture.w745 (w746);
CREATE INDEX IF NOT EXISTS w754
    ON fixture.w745 (w751);
CREATE INDEX IF NOT EXISTS w755
    ON fixture.w745 (w749, w750);

CREATE TABLE IF NOT EXISTS fixture.w756 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w026       uuid NOT NULL,
    w757        text NOT NULL,
    w074          text NOT NULL DEFAULT 'W278',
    w266   integer NOT NULL DEFAULT 0,
    w267     uuid,
    w146 text NOT NULL,
    data            jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w758
    ON fixture.w756 (w026, w146);
CREATE INDEX IF NOT EXISTS w759
    ON fixture.w756 ((fixture.w727(data->>'w277')))
    WHERE (w074 = 'W278');
CREATE INDEX IF NOT EXISTS w760
    ON fixture.w756 ((fixture.w727(data->>'w274')))
    WHERE (w074 = 'W275');
CREATE INDEX IF NOT EXISTS w761
    ON fixture.w756 (w026);

CREATE TABLE IF NOT EXISTS fixture.w762 (
    w026                uuid NOT NULL,
    w292               uuid NOT NULL DEFAULT uuidv7(),
    w763             text,
    w028               text,
    w764             text,
    w765 text,
    data                     jsonb NOT NULL,
    CONSTRAINT w766 PRIMARY KEY (w026, w292)
);

CREATE UNIQUE INDEX IF NOT EXISTS w767
    ON fixture.w762 (w026, w765);
CREATE INDEX IF NOT EXISTS w768
    ON fixture.w762 (w026, (fixture.w727(data->>'w090')));

CREATE OR REPLACE FUNCTION fixture.w769() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.w765 IS NOT NULL
        AND (char_length(NEW.w765) NOT BETWEEN 1 AND 256
             OR btrim(NEW.w765) = '') THEN
        RAISE EXCEPTION 'w770: w771 w647 key w010 w017 1..256 w054-w055 w056'
            USING ERRCODE = 'w012';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS w772 ON fixture.w762;
CREATE TRIGGER w772
    BEFORE INSERT OR UPDATE ON fixture.w762
    FOR EACH ROW EXECUTE FUNCTION fixture.w769();

CREATE TABLE IF NOT EXISTS fixture.w298 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL,
    w074    text NOT NULL,
    data      jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w299
    ON fixture.w298 (w026) WHERE (w074 = 'W300');
CREATE INDEX IF NOT EXISTS w301
    ON fixture.w298 (w026, (fixture.w727(data->>'w302')) DESC);

CREATE TABLE IF NOT EXISTS fixture.w773 (
    w026 uuid PRIMARY KEY,
    data      jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS fixture.w774 (
    w026     uuid NOT NULL,
    w292    uuid NOT NULL,
    w175      uuid NOT NULL,
    w074        text NOT NULL,
    w775     uuid,
    w776 uuid,
    w266 integer NOT NULL DEFAULT 0,
    data          jsonb NOT NULL,
    CONSTRAINT w777 PRIMARY KEY (w026, w292, w175),
    CONSTRAINT w778
        FOREIGN KEY (w026, w292, w175)
        REFERENCES fixture.w742 (w026, w292, w175) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS w779
    ON fixture.w774 (w026, w292, w074);
CREATE INDEX IF NOT EXISTS w780
    ON fixture.w774 (w775);

CREATE TABLE IF NOT EXISTS fixture.w781 (
    w027           uuid PRIMARY KEY DEFAULT uuidv7(),
    w026    uuid NOT NULL,
    w292   uuid NOT NULL,
    w782 text NOT NULL,
    data         jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w783
    ON fixture.w781 (w026, w292, (fixture.w727(data->>'w090')));
CREATE INDEX IF NOT EXISTS w784
    ON fixture.w781 (w026, w782, (fixture.w727(data->>'w090')));

CREATE TABLE IF NOT EXISTS fixture.w785 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL,
    w292 uuid NOT NULL,
    data       jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w786
    ON fixture.w785 (w026, w292);

CREATE OR REPLACE VIEW fixture.w787 AS
SELECT w027,
       w026,
       w292,
       w729,
       w730,
       data->>'w788'                                AS w788,
       w128,
       fixture.w727(data->>'w090')         AS w090,
       fixture.w727(data->>'w734') AS w734
FROM fixture.w728;

CREATE OR REPLACE VIEW fixture.w789 AS
SELECT w027                                                       AS w293,
       w026,
       w292,
       data->>'w790'                                      AS w790,
       (data->>'w791')::integer                         AS w791,
       fixture.w727(data->>'w792')            AS w792,
       data->>'w747'                                 AS w747,
       data->>'w748'                                  AS w748,
       data->>'w749'                          AS w749,
       data->>'w750'                           AS w750,
       data->'w793'                                       AS w793,
       (data->>'w794')::boolean                             AS w794,
       data->'w795'                                      AS w795,
       data->'w796'                                        AS w796,
       COALESCE(data->'w797', '[]'::jsonb)           AS w797,
       data->'w798'                                   AS w798,
       COALESCE(data->'w799', '[]'::jsonb)               AS w799,
       fixture.w727(data->>'w090')       AS w090
FROM fixture.w735;

CREATE OR REPLACE VIEW fixture.w800 AS
SELECT w027                                                       AS w294,
       w293,
       w292,
       w729,
       w730,
       action,
       (data->>'w801')::float8                                  AS w801,
       data->>'w136'                                           AS w136,
       data->>'w747'                                  AS w747,
       fixture.w727(data->>'w090')       AS w090,
       data->>'w802'                                      AS w802,
       COALESCE(data->'w803', '[]'::jsonb)                      AS w803,
       data->'w702'                                           AS w702
FROM fixture.w094;

CREATE OR REPLACE VIEW fixture.w804 AS
SELECT w026,
       w292,
       w175,
       fixture.w727(data->>'w090') AS w090
FROM fixture.w742;

CREATE OR REPLACE VIEW fixture.w805 AS
SELECT w027,
       w746,
       w747,
       w748,
       w749,
       w750,
       data->'w806'                                   AS w806,
       data->'w807'                                    AS w807,
       w751,
       w752,
       fixture.w727(data->>'w090')       AS w090
FROM fixture.w745;

CREATE OR REPLACE VIEW fixture.w808 AS
SELECT w027,
       w026,
       w757,
       data->'w271'                                          AS w271,
       w146,
       w074,
       w266,
       fixture.w727(data->>'w277')     AS w277,
       w267,
       fixture.w727(data->>'w274') AS w274,
       fixture.w727(data->>'w670')    AS w670,
       data->>'w339'                                      AS w339,
       data->>'w341'                                      AS w341,
       (data->>'w776')::uuid                           AS w776,
       fixture.w727(data->>'w090')       AS w090,
       fixture.w727(data->>'w161')       AS w161
FROM fixture.w756;

CREATE OR REPLACE VIEW fixture.w809 AS
SELECT w026,
       w292,
       w763,
       w028,
       w764,
       data->>'w810'                            AS w810,
       w765,
       fixture.w727(data->>'w090')       AS w090,
       fixture.w727(data->>'w161')       AS w161
FROM fixture.w762;

CREATE OR REPLACE VIEW fixture.w350 AS
SELECT w027                                                       AS w544,
       w026,
       fixture.w727(data->>'w302')     AS w302,
       fixture.w727(data->>'w337')     AS w337,
       w074,
       data->>'w344'                                           AS w344
FROM fixture.w298;

CREATE OR REPLACE VIEW fixture.w811 AS
SELECT w026,
       (data->>'w812')::numeric                     AS w812,
       fixture.w727(data->>'w813')   AS w813,
       fixture.w727(data->>'w814')  AS w814,
       (data->>'w815')::bigint                            AS w815
FROM fixture.w773;

CREATE OR REPLACE VIEW fixture.w816 AS
SELECT w026,
       w292,
       w175,
       w074,
       w775,
       w776,
       w266,
       data->>'w517'                                         AS w517,
       fixture.w727(data->>'w512')       AS w512,
       fixture.w727(data->>'w337')     AS w337,
       fixture.w727(data->>'w090')       AS w090,
       fixture.w727(data->>'w161')       AS w161
FROM fixture.w774;

CREATE OR REPLACE VIEW fixture.w817 AS
SELECT w027,
       w026,
       w292,
       w782,
       (data->>'w818')::uuid                         AS w818,
       fixture.w727(data->>'w090')       AS w090
FROM fixture.w781;

CREATE OR REPLACE VIEW fixture.w819 AS
SELECT w027,
       w026,
       w292,
       data->>'w317'                                      AS w317,
       fixture.w727(data->>'w318')       AS w318
FROM fixture.w785;

SELECT pg_advisory_unlock(hashtext('w001.fixture.schema'));
