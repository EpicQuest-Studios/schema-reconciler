-- widened managed-object coverage: enum, domain, materialized view with an
-- index, a row-level-security policy and comments on managed targets.
CREATE SCHEMA fixture;
CREATE TYPE fixture.mood AS ENUM ('sad', 'ok', 'happy');
CREATE DOMAIN fixture.posint AS integer NOT NULL DEFAULT 1 CHECK (VALUE > 0);
CREATE TABLE fixture.t (
    id fixture.posint PRIMARY KEY,
    mood fixture.mood NOT NULL DEFAULT 'ok',
    note text
);
ALTER TABLE fixture.t ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_sel ON fixture.t FOR SELECT USING (id > 0);
COMMENT ON TABLE fixture.t IS 'governed table';
COMMENT ON COLUMN fixture.t.id IS 'primary identifier';
COMMENT ON POLICY p_sel ON fixture.t IS 'select restriction';
CREATE MATERIALIZED VIEW fixture.mv AS SELECT id, mood, note FROM fixture.t;
CREATE MATERIALIZED VIEW fixture.mv_empty AS SELECT id FROM fixture.t WITH NO DATA;
CREATE INDEX mv_mood ON fixture.mv (mood);
COMMENT ON MATERIALIZED VIEW fixture.mv IS 'denormalised read model';
