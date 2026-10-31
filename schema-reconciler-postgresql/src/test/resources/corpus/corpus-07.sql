SELECT pg_advisory_lock(hashtext('w001.w545-w546.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE OR REPLACE FUNCTION fixture.w548(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

CREATE OR REPLACE FUNCTION fixture.w549() RETURNS uuid AS $$
BEGIN
    RETURN NULLIF(current_setting('app.w550', true), '')::uuid;
EXCEPTION
    WHEN OTHERS THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE SECURITY INVOKER;

GRANT EXECUTE ON FUNCTION fixture.w549() TO w547;
GRANT EXECUTE ON FUNCTION fixture.w548(text) TO w547;

CREATE TABLE IF NOT EXISTS fixture.w470 (
    w027     uuid PRIMARY KEY DEFAULT uuidv7(),
    name   text NOT NULL,
    w074 text NOT NULL,
    data   jsonb NOT NULL,
    CONSTRAINT w551 UNIQUE (name)
);

GRANT SELECT, INSERT, UPDATE ON TABLE fixture.w470 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w552 (
    w027           uuid PRIMARY KEY DEFAULT uuidv7(),
    w026    uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w411 text NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT w553 UNIQUE (w026, w411)
);

ALTER TABLE fixture.w552 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w552 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w552;
CREATE POLICY w554 ON fixture.w552
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w552 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w555 (
    w027                 uuid PRIMARY KEY DEFAULT uuidv7(),
    w026          uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w556 text NOT NULL,
    name               text NOT NULL,
    w557           int NOT NULL,
    w074             text NOT NULL,
    w558       text,
    w559             text NOT NULL,
    w560    int NOT NULL,
    data               jsonb NOT NULL,
    CONSTRAINT w561 UNIQUE (w026, name),
    CONSTRAINT w562 UNIQUE (w027, w026)
);

CREATE INDEX IF NOT EXISTS w563
    ON fixture.w555 (w026, w074);
CREATE INDEX IF NOT EXISTS w564
    ON fixture.w555 (w026, w556);
CREATE INDEX IF NOT EXISTS w565
    ON fixture.w555 (w026, w074, w559);

CREATE TABLE IF NOT EXISTS fixture.w566 (
    w027                  uuid PRIMARY KEY DEFAULT uuidv7(),
    w026           uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w567 uuid NOT NULL,
    version             int NOT NULL,
    data                jsonb NOT NULL,
    CONSTRAINT w568
        UNIQUE (w567, w026, version),
    CONSTRAINT w569 FOREIGN KEY (w567, w026)
        REFERENCES fixture.w555 (w027, w026) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS w570
    ON fixture.w566 (w026, w567);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'w571'
          AND conrelid = 'fixture.w555'::regclass
    ) THEN
        ALTER TABLE fixture.w555
            ADD CONSTRAINT w571
            FOREIGN KEY (w027, w026, w560)
            REFERENCES fixture.w566 (w567, w026, version)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS fixture.w572 (
    w027                  uuid PRIMARY KEY DEFAULT uuidv7(),
    w026           uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w567 uuid NOT NULL,
    data                jsonb NOT NULL,
    CONSTRAINT w573 FOREIGN KEY (w567, w026)
        REFERENCES fixture.w555 (w027, w026) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS w574
    ON fixture.w572 (w026, w567,
        fixture.w548(data ->> 'w090') DESC);

CREATE OR REPLACE FUNCTION fixture.w575()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'W545 w576 w577 w092 immutable and w101 w017 w578';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w579
BEFORE UPDATE ON fixture.w566
FOR EACH ROW EXECUTE FUNCTION fixture.w575();

CREATE OR REPLACE FUNCTION fixture.w580()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.w074 = 'W581') <> (NEW.w558 IS NOT NULL) THEN
        RAISE EXCEPTION 'w074 W581 and w558 w010 w017 set w582';
    END IF;
    IF (NEW.w559 = 'W583') <> (NEW.data ->> 'w584' IS NOT NULL) THEN
        RAISE EXCEPTION 'w559 W583 and w584 w010 w017 set w582';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w585
BEFORE INSERT OR UPDATE ON fixture.w555
FOR EACH ROW EXECUTE FUNCTION fixture.w580();

CREATE OR REPLACE FUNCTION fixture.w586()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'W545 w484 w587 w092 immutable and w101 w017 w578';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w588
BEFORE UPDATE ON fixture.w572
FOR EACH ROW EXECUTE FUNCTION fixture.w586();

ALTER TABLE fixture.w555 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w555 FORCE ROW LEVEL SECURITY;
ALTER TABLE fixture.w566 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w566 FORCE ROW LEVEL SECURITY;
ALTER TABLE fixture.w572 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w572 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w555;
CREATE POLICY w554 ON fixture.w555
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());
DROP POLICY IF EXISTS w554 ON fixture.w566;
CREATE POLICY w554 ON fixture.w566
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());
DROP POLICY IF EXISTS w554 ON fixture.w572;
CREATE POLICY w554 ON fixture.w572
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w555 TO w547;
GRANT SELECT, INSERT, DELETE ON TABLE fixture.w566 TO w547;
GRANT SELECT, INSERT, DELETE ON TABLE fixture.w572 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w589 (
    w027           uuid PRIMARY KEY DEFAULT uuidv7(),
    w026    uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    name         text NOT NULL,
    w074       text NOT NULL,
    w558 text,
    w559       text NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT w590 UNIQUE (w026, name),
    CONSTRAINT w591 UNIQUE (w027, w026)
);

CREATE INDEX IF NOT EXISTS w592
    ON fixture.w589 (w026, w074, w559);

CREATE TABLE IF NOT EXISTS fixture.w593 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w594 uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w595 FOREIGN KEY (w594, w026)
        REFERENCES fixture.w589 (w027, w026) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS w596
    ON fixture.w593 (w026, w594,
        fixture.w548(data ->> 'w090') DESC);

CREATE OR REPLACE FUNCTION fixture.w597()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('app.w598', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'W417 w484 w587 w092 immutable and w101 w017 w578 or w184';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w599
BEFORE UPDATE OR DELETE ON fixture.w593
FOR EACH ROW EXECUTE FUNCTION fixture.w597();

CREATE OR REPLACE FUNCTION fixture.w600()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.w074 = 'W581') <> (NEW.w558 IS NOT NULL) THEN
        RAISE EXCEPTION 'w074 W581 and w558 w010 w017 set w582';
    END IF;
    IF (NEW.w559 = 'W583') <> (NEW.data ->> 'w584' IS NOT NULL) THEN
        RAISE EXCEPTION 'w559 W583 and w584 w010 w017 set w582';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w601
BEFORE INSERT OR UPDATE ON fixture.w589
FOR EACH ROW EXECUTE FUNCTION fixture.w600();

ALTER TABLE fixture.w589 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w589 FORCE ROW LEVEL SECURITY;
ALTER TABLE fixture.w593 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w593 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w589;
CREATE POLICY w554 ON fixture.w589
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());
DROP POLICY IF EXISTS w554 ON fixture.w593;
CREATE POLICY w554 ON fixture.w593
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w589 TO w547;
GRANT SELECT, INSERT, DELETE ON TABLE fixture.w593 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w602 (
    w026 uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w594 uuid NOT NULL,
    version   int NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w603 PRIMARY KEY (w026, w594),
    CONSTRAINT w604 FOREIGN KEY (w594, w026)
        REFERENCES fixture.w589 (w027, w026) ON DELETE CASCADE
);

ALTER TABLE fixture.w602 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w602 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w602;
CREATE POLICY w554 ON fixture.w602
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w602 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w605 (
    w027                  uuid PRIMARY KEY DEFAULT uuidv7(),
    w026           uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w594           uuid NOT NULL,
    w606  uuid NOT NULL,
    w607 uuid,
    data                jsonb NOT NULL,
    CONSTRAINT w608 UNIQUE (w027, w594, w026),
    CONSTRAINT w609 FOREIGN KEY (w594, w026)
        REFERENCES fixture.w589 (w027, w026) ON DELETE RESTRICT,
    CONSTRAINT w610 FOREIGN KEY (w607, w594, w026)
        REFERENCES fixture.w605 (w027, w594, w026) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS w611
    ON fixture.w605 (w026, w594)
    WHERE data ->> 'w468' IS NULL;
CREATE INDEX IF NOT EXISTS w612
    ON fixture.w605 (w026, fixture.w548(data ->> 'w440'))
    WHERE data ->> 'w468' IS NULL;

CREATE OR REPLACE FUNCTION fixture.w613()
RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('app.w598', true) = 'on' THEN
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.w026 <> OLD.w026
            OR NEW.w594 <> OLD.w594
            OR NEW.w606 <> OLD.w606
            OR NEW.w607 IS DISTINCT FROM OLD.w607
            OR (NEW.data - 'w614' - 'w440' - 'w468' - 'w502'
                - 'w615')
               IS DISTINCT FROM
               (OLD.data - 'w614' - 'w440' - 'w468' - 'w502'
                - 'w615') THEN
            RAISE EXCEPTION 'W417 session identity is immutable';
        END IF;
        IF OLD.data ->> 'w468' IS NOT NULL THEN
            RAISE EXCEPTION 'W469 w417 w523 w101 w017 w616 or w617';
        END IF;
    END IF;
    IF (NEW.data ->> 'w468' IS NULL) <> (NEW.data ->> 'w502' IS NULL) THEN
        RAISE EXCEPTION 'w468 and w502 w010 w017 set w582';
    END IF;
    IF fixture.w548(NEW.data ->> 'w440')
        <= fixture.w548(NEW.data ->> 'w618') THEN
        RAISE EXCEPTION 'w440 w010 w017 after w618';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w619
BEFORE INSERT OR UPDATE ON fixture.w605
FOR EACH ROW EXECUTE FUNCTION fixture.w613();

ALTER TABLE fixture.w605 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w605 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w605;
CREATE POLICY w554 ON fixture.w605
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w605 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w620 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w594  uuid NOT NULL,
    w292 uuid NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT w621 FOREIGN KEY (w292, w594, w026)
        REFERENCES fixture.w605 (w027, w594, w026) ON DELETE RESTRICT
);

CREATE INDEX IF NOT EXISTS w622
    ON fixture.w620 (w026, w292,
        fixture.w548(data ->> 'w090'));

CREATE OR REPLACE FUNCTION fixture.w623()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('app.w598', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'W417 session w145 w092 immutable and w101 w017 w578 or w184';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w624
BEFORE UPDATE OR DELETE ON fixture.w620
FOR EACH ROW EXECUTE FUNCTION fixture.w623();

CREATE OR REPLACE FUNCTION fixture.w625()
RETURNS TRIGGER AS $$
BEGIN
    IF (NEW.data ->> 'w626' = 'W469') <> (NEW.data ->> 'w502' IS NOT NULL) THEN
        RAISE EXCEPTION 'Session close w136 is w627 w628 for W469 w145';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w629
BEFORE INSERT ON fixture.w620
FOR EACH ROW EXECUTE FUNCTION fixture.w625();

ALTER TABLE fixture.w620 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w620 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w620;
CREATE POLICY w554 ON fixture.w620
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, DELETE ON TABLE fixture.w620 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w630 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w594  uuid NOT NULL,
    w292 uuid NOT NULL,
    w631       text NOT NULL,
    w632    text NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT w633 FOREIGN KEY (w292, w594, w026)
        REFERENCES fixture.w605 (w027, w594, w026) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX IF NOT EXISTS w634
    ON fixture.w630 (w292, w631, w632)
    WHERE data ->> 'w635' IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS w636
    ON fixture.w630 (w292)
    WHERE w631 = 'W637' AND data ->> 'w635' IS NULL;
CREATE INDEX IF NOT EXISTS w638
    ON fixture.w630 (w026, w292)
    WHERE data ->> 'w635' IS NULL;

CREATE OR REPLACE FUNCTION fixture.w639()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF current_setting('app.w598', true) = 'on' THEN
            RETURN OLD;
        END IF;
        RAISE EXCEPTION 'W417 w640 w101 w017 w184';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF fixture.w548(NEW.data ->> 'w440')
            <= fixture.w548(NEW.data ->> 'w641') THEN
            RAISE EXCEPTION 'W642 w440 w010 w017 after w641';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.data ->> 'w635' IS NOT NULL THEN
        RAISE EXCEPTION 'W643 w417 w640 w101 w017 w616';
    END IF;
    IF NEW.data ->> 'w635' IS NULL
        OR NEW.w027 <> OLD.w027
        OR NEW.w026 <> OLD.w026
        OR NEW.w594 <> OLD.w594
        OR NEW.w292 <> OLD.w292
        OR NEW.w631 <> OLD.w631
        OR NEW.w632 <> OLD.w632
        OR (NEW.data - 'w635') IS DISTINCT FROM (OLD.data - 'w635') THEN
        RAISE EXCEPTION 'W417 w640 w092 immutable; only w644 is w645';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w646
BEFORE INSERT OR UPDATE OR DELETE ON fixture.w630
FOR EACH ROW EXECUTE FUNCTION fixture.w639();

ALTER TABLE fixture.w630 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w630 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w630;
CREATE POLICY w554 ON fixture.w630
    AS PERMISSIVE FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w630 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w647 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w026       uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w648    text NOT NULL,
    w649 text NOT NULL,
    w650  text NOT NULL,
    w146 text NOT NULL,
    w651    text NOT NULL,
    data            jsonb NOT NULL,
    CONSTRAINT w652
        UNIQUE (w026, w648, w649, w650, w146),
    CONSTRAINT w653 UNIQUE (w027, w026)
);

CREATE OR REPLACE FUNCTION fixture.w654()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.w027 <> OLD.w027 OR NEW.w026 <> OLD.w026
       OR NEW.w648 <> OLD.w648 OR NEW.w649 <> OLD.w649
       OR NEW.w650 <> OLD.w650 OR NEW.w146 <> OLD.w146
       OR NEW.w651 <> OLD.w651
       OR (NEW.data - 'w074' - 'w655' - 'w656')
          IS DISTINCT FROM (OLD.data - 'w074' - 'w655' - 'w656') THEN
        RAISE EXCEPTION 'W647 record identity and w657 w658 w092 immutable';
    END IF;

    IF OLD.data ->> 'w074' = 'COMMITTED' THEN
        RAISE EXCEPTION 'Committed w647 w587 w101 w017 w616';
    END IF;

    IF OLD.data ->> 'w074' = 'W659' AND NEW.data ->> 'w074' = 'COMMITTED' THEN
        IF NEW.data -> 'w655' IS NULL OR NEW.data -> 'w655' = '{}'::jsonb THEN
            RAISE EXCEPTION 'Committed w647 record w043 w054-empty w655';
        END IF;
        NEW.data := NEW.data || jsonb_build_object('w656', now());
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'W660 w647 w074 w099: % -> %',
        OLD.data ->> 'w074', NEW.data ->> 'w074';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w661
    BEFORE UPDATE ON fixture.w647
    FOR EACH ROW EXECUTE FUNCTION fixture.w654();

CREATE OR REPLACE FUNCTION fixture.w662()
RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('app.w598', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'W663 of w647 w587 is w664';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w665
    BEFORE DELETE ON fixture.w647
    FOR EACH ROW EXECUTE FUNCTION fixture.w662();

ALTER TABLE fixture.w647 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w647 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w647;
CREATE POLICY w554 ON fixture.w647
    FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w647 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w666 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w667 text,
    data       jsonb NOT NULL,
    CONSTRAINT w668 UNIQUE (w027, w026)
);

CREATE INDEX IF NOT EXISTS w669
    ON fixture.w666 (w026, fixture.w548(data ->> 'w090') ASC)
    WHERE data ->> 'w670' IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS w671
    ON fixture.w666 (w026, w667)
    WHERE w667 IS NOT NULL;

CREATE OR REPLACE FUNCTION fixture.w672()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.w027 <> OLD.w027 OR NEW.w026 <> OLD.w026
       OR NEW.w667 IS DISTINCT FROM OLD.w667
       OR (NEW.data - 'w670') IS DISTINCT FROM (OLD.data - 'w670') THEN
        RAISE EXCEPTION 'W666 event w081 and w271 w092 immutable';
    END IF;

    IF OLD.data ->> 'w670' IS NOT NULL
        AND NEW.data ->> 'w670' IS DISTINCT FROM OLD.data ->> 'w670' THEN
        RAISE EXCEPTION 'W666 w670 is immutable w673 set (w101 w017 w616 or w674)';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w675
    BEFORE UPDATE ON fixture.w666
    FOR EACH ROW EXECUTE FUNCTION fixture.w672();

CREATE OR REPLACE FUNCTION fixture.w676()
RETURNS TRIGGER AS $$
BEGIN
    IF current_setting('app.w598', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'W663 of w666 w145 is w664';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER w677
    BEFORE DELETE ON fixture.w666
    FOR EACH ROW EXECUTE FUNCTION fixture.w676();

ALTER TABLE fixture.w666 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w666 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w666;
CREATE POLICY w554 ON fixture.w666
    FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE fixture.w666 TO w547;

CREATE TABLE IF NOT EXISTS fixture.w678 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL REFERENCES fixture.w470(w027) ON DELETE CASCADE,
    w679       uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w680 UNIQUE (w026, w679)
);

CREATE INDEX IF NOT EXISTS w681
    ON fixture.w678
    (w026, fixture.w548(data ->> 'w682'));

ALTER TABLE fixture.w678 ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture.w678 FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS w554 ON fixture.w678;
CREATE POLICY w554 ON fixture.w678
    FOR ALL TO w547
    USING (w026 = fixture.w549())
    WITH CHECK (w026 = fixture.w549());

GRANT SELECT, INSERT, DELETE ON TABLE fixture.w678 TO w547;

CREATE OR REPLACE VIEW fixture.w503 WITH (security_invoker = true) AS
SELECT w027, name, w074,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w161') AS w161
FROM fixture.w470;

CREATE OR REPLACE VIEW fixture.w683 WITH (security_invoker = true) AS
SELECT w027, w026, w411,
       data ->> 'w648' AS w648,
       data ->> 'w684' AS w684,
       data ->> 'w074' AS w074,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w161') AS w161
FROM fixture.w552;

CREATE OR REPLACE VIEW fixture.w685 WITH (security_invoker = true) AS
SELECT w027, w026, w556, name,
       data ->> 'w686' AS w686,
       data ->> 'w687' AS w687,
       data ->> 'w688' AS w688,
       data -> 'w689' AS w689,
       data -> 'w690' AS w690,
       data -> 'w691' AS w691,
       data -> 'w692' AS w692,
       data ->> 'w693' AS w693,
       data -> 'w694' AS w694,
       data -> 'w695' AS w695,
       w557, w074, w558, w559,
       (data ->> 'w696')::int AS w696,
       fixture.w548(data ->> 'w697') AS w697,
       fixture.w548(data ->> 'w698') AS w698,
       fixture.w548(data ->> 'w584') AS w584,
       w560,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w161') AS w161
FROM fixture.w555;

CREATE OR REPLACE VIEW fixture.w699 WITH (security_invoker = true) AS
SELECT w027, w026, w567, version,
       data ->> 'digest' AS digest,
       data -> 'snapshot' AS snapshot,
       data ->> 'w307' AS w307,
       fixture.w548(data ->> 'w090') AS w090
FROM fixture.w566;

CREATE OR REPLACE VIEW fixture.w700 WITH (security_invoker = true) AS
SELECT w027, w026, w567,
       data ->> 'action' AS action,
       data ->> 'w701' AS w701,
       data -> 'w702' AS w702,
       fixture.w548(data ->> 'w090') AS w090
FROM fixture.w572;

CREATE OR REPLACE VIEW fixture.w703 WITH (security_invoker = true) AS
SELECT w027, w026,
       data ->> 'w556' AS w556,
       name,
       data ->> 'w704' AS w704,
       data -> 'w705' AS w705,
       data -> 'w706' AS w706,
       w074, w558,
       (data ->> 'w707')::int AS w707,
       (data ->> 'w708')::int AS w708,
       w559,
       (data ->> 'w696')::int AS w696,
       fixture.w548(data ->> 'w697') AS w697,
       fixture.w548(data ->> 'w698') AS w698,
       fixture.w548(data ->> 'w584') AS w584,
       data -> 'w709' AS w709,
       fixture.w548(data ->> 'w710') AS w710,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w161') AS w161
FROM fixture.w589;

CREATE OR REPLACE VIEW fixture.w711 WITH (security_invoker = true) AS
SELECT w027, w026, w594,
       data ->> 'action' AS action,
       data ->> 'w701' AS w701,
       data -> 'w702' AS w702,
       fixture.w548(data ->> 'w090') AS w090
FROM fixture.w593;

CREATE OR REPLACE VIEW fixture.w712 WITH (security_invoker = true) AS
SELECT w026, w594, version,
       data -> 'w713' AS w713,
       data ->> 'w714' AS w714,
       fixture.w548(data ->> 'w161') AS w161,
       fixture.w548(data ->> 'w090') AS w090,
       (data ->> 'w715')::int AS w715,
       fixture.w548(data ->> 'w716') AS w716
FROM fixture.w602;

CREATE OR REPLACE VIEW fixture.w717 WITH (security_invoker = true) AS
SELECT w027, w026, w594, w606,
       data ->> 'w718' AS w718,
       data ->> 'w719' AS w719,
       w607,
       fixture.w548(data ->> 'w618') AS w618,
       fixture.w548(data ->> 'w614') AS w614,
       fixture.w548(data ->> 'w440') AS w440,
       fixture.w548(data ->> 'w468') AS w468,
       data ->> 'w502' AS w502,
       data -> 'w615' AS w615
FROM fixture.w605;

CREATE OR REPLACE VIEW fixture.w720 WITH (security_invoker = true) AS
SELECT w027, w026, w594, w292,
       data ->> 'w626' AS w626,
       data ->> 'w502' AS w502,
       data -> 'w702' AS w702,
       fixture.w548(data ->> 'w090') AS w090
FROM fixture.w620;

CREATE OR REPLACE VIEW fixture.w721 WITH (security_invoker = true) AS
SELECT w027, w026, w594, w292, w631, w632,
       fixture.w548(data ->> 'w641') AS w641,
       fixture.w548(data ->> 'w440') AS w440,
       fixture.w548(data ->> 'w635') AS w635
FROM fixture.w630;

CREATE OR REPLACE VIEW fixture.w722 WITH (security_invoker = true) AS
SELECT w027, w026, w648, w649, w650,
       w146, w651,
       data ->> 'w074' AS w074,
       data -> 'w655' AS w655,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w656') AS w656
FROM fixture.w647;

CREATE OR REPLACE VIEW fixture.w723 WITH (security_invoker = true) AS
SELECT w027, w026,
       data ->> 'w724' AS w724,
       (data ->> 'w725')::uuid AS w725,
       data ->> 'w626' AS w626,
       w667,
       data -> 'w271' AS w271,
       fixture.w548(data ->> 'w090') AS w090,
       fixture.w548(data ->> 'w670') AS w670
FROM fixture.w666;

CREATE OR REPLACE VIEW fixture.w726 WITH (security_invoker = true) AS
SELECT w027, w026, w679,
       fixture.w548(data ->> 'w682') AS w682
FROM fixture.w678;

GRANT SELECT ON fixture.w503 TO w547;
GRANT SELECT ON fixture.w683 TO w547;
GRANT SELECT ON fixture.w685 TO w547;
GRANT SELECT ON fixture.w699 TO w547;
GRANT SELECT ON fixture.w700 TO w547;
GRANT SELECT ON fixture.w703 TO w547;
GRANT SELECT ON fixture.w711 TO w547;
GRANT SELECT ON fixture.w712 TO w547;
GRANT SELECT ON fixture.w717 TO w547;
GRANT SELECT ON fixture.w720 TO w547;
GRANT SELECT ON fixture.w721 TO w547;
GRANT SELECT ON fixture.w722 TO w547;
GRANT SELECT ON fixture.w723 TO w547;
GRANT SELECT ON fixture.w726 TO w547;
