SELECT pg_advisory_lock(hashtext('w001.w002.schema'));

CREATE SCHEMA IF NOT EXISTS fixture;

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION fixture.w003(t text) RETURNS timestamptz
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS 'SELECT t::timestamptz';

DROP FUNCTION IF EXISTS fixture.w004() CASCADE;
CREATE OR REPLACE FUNCTION fixture.w005() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.w006 = NEW.w007 THEN
    RAISE EXCEPTION 'w002 w008 w009 w010 w011'
      USING ERRCODE = 'w012', CONSTRAINT = 'w013';
  END IF;
  IF NEW.w014 = 'W015' AND NEW.w006 > NEW.w007 THEN
    RAISE EXCEPTION 'W015 w016 w010 w017 stored with w006 < w007'
      USING ERRCODE = 'w012', CONSTRAINT = 'w018';
  END IF;
  IF NEW.w014 NOT IN ('W019', 'W015', 'W020', 'W021') THEN
    RAISE EXCEPTION 'unknown w008 type %', NEW.w014
      USING ERRCODE = 'w012', CONSTRAINT = 'w022';
  END IF;
  IF NEW.data->>'w023' IS NULL
     OR NOT ((NEW.data->>'w023')::float8 BETWEEN 0 AND 1) THEN
    RAISE EXCEPTION 'w008 w023 w010 w017 between 0 and 1'
      USING ERRCODE = 'w012', CONSTRAINT = 'w024';
  END IF;
  IF EXISTS (
    SELECT 1 FROM fixture.w025 m
    WHERE m.w026 = NEW.w026
      AND m.w027 IN (NEW.w006, NEW.w007)
      AND m.w028 IS DISTINCT FROM NEW.w028
  ) THEN
    RAISE EXCEPTION 'w002 w008 w009 w010 share w029 w008 w030 (w028 %)', NEW.w028
      USING ERRCODE = 'w012', CONSTRAINT = 'w031';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w032() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NOT ((NEW.w030 = 'W033' AND NEW.w028 IS NOT NULL)
          OR (NEW.w030 = 'W034' AND NEW.w028 IS NULL)) THEN
    RAISE EXCEPTION 'w002 w030 w010 w017 W033 with a w028 or W034 without w035'
      USING ERRCODE = 'w012', CONSTRAINT = 'w036';
  END IF;
  IF NEW.data->>'w037' IS NOT NULL
     AND NOT (fixture.w003(NEW.data->>'w037')
              > fixture.w003(NEW.data->>'w038')) THEN
    RAISE EXCEPTION 'w037 w010 w017 w039 after w038'
      USING ERRCODE = 'w012', CONSTRAINT = 'w040';
  END IF;
  IF NEW.data->>'w041' IS NOT NULL
     AND (NEW.data->>'w042' IS NULL
          OR NOT (fixture.w003(NEW.data->>'w041')
                  > fixture.w003(NEW.data->>'w042'))) THEN
    RAISE EXCEPTION 'w041 w043 w042 and w010 w017 w039 after w044'
      USING ERRCODE = 'w012', CONSTRAINT = 'w045';
  END IF;
  IF NEW.data->'w046' IS NOT NULL
     AND (jsonb_typeof(NEW.data->'w046') <> 'array'
          OR jsonb_array_length(NEW.data->'w046') > 8) THEN
    RAISE EXCEPTION 'w002 w046 w010 w017 w047 array of at w048 8 w049'
      USING ERRCODE = 'w012', CONSTRAINT = 'w050';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w051() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NOT ((NEW.w030 = 'W033' AND NEW.w028 IS NOT NULL)
          OR (NEW.w030 = 'W034' AND NEW.w028 IS NULL)) THEN
    RAISE EXCEPTION 'document w030 w010 w017 W033 with a w028 or W034 without w035'
      USING ERRCODE = 'w012', CONSTRAINT = 'w052';
  END IF;
  IF btrim(COALESCE(NEW.data->>'w053', '')) = ''
     OR char_length(btrim(NEW.data->>'w053')) > 128 THEN
    RAISE EXCEPTION 'w053 w010 w017 1..128 w054-w055 w056'
      USING ERRCODE = 'w012', CONSTRAINT = 'w057';
  END IF;
  IF NEW.data->'w046' IS NOT NULL
     AND (jsonb_typeof(NEW.data->'w046') <> 'array'
          OR jsonb_array_length(NEW.data->'w046') > 8) THEN
    RAISE EXCEPTION 'document w046 w010 w017 w047 array of at w048 8 w049'
      USING ERRCODE = 'w012', CONSTRAINT = 'w058';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w059() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.w060 = '' THEN
    RAISE EXCEPTION 'w061 w062 w010 not w017 w055'
      USING ERRCODE = 'w012', CONSTRAINT = 'w063';
  END IF;
  IF NEW.w060 = NEW.w064 THEN
    RAISE EXCEPTION 'w061 w062 w010 not w017 w029 w065 w066'
      USING ERRCODE = 'w012', CONSTRAINT = 'w067';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w068() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF btrim(NEW.w069) = '' OR btrim(COALESCE(NEW.data->>'w070', '')) = '' THEN
      RAISE EXCEPTION 'w071 text and normalized w071 w010 w017 w054-w055'
        USING ERRCODE = 'w012', CONSTRAINT = 'w072';
    END IF;
    IF NEW.w073 = 'ADMIN' THEN
      IF NEW.w074 <> 'W075'
         OR NEW.data->>'w076' IS NULL OR NEW.data->>'w077' IS NULL
         OR NEW.data->>'w078' IS NOT NULL THEN
        RAISE EXCEPTION 'ADMIN w071 w010 w017 w079 W075 with w080 w081 and no w082 w083'
          USING ERRCODE = 'w012', CONSTRAINT = 'w084';
      END IF;
    ELSIF NEW.w073 = 'W085' THEN
      IF NEW.w074 <> 'W086'
         OR NEW.data->>'w078' IS NULL
         OR NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
         OR NEW.data->>'w087' IS NOT NULL THEN
        RAISE EXCEPTION 'W085 w071 w010 w017 w079 W086 with w082 w081 and no w080 w083'
          USING ERRCODE = 'w012', CONSTRAINT = 'w084';
      END IF;
    ELSE
      RAISE EXCEPTION 'unknown w071 w073 %', NEW.w073
        USING ERRCODE = 'w012', CONSTRAINT = 'w084';
    END IF;
    RETURN NEW;
  END IF;

  
  IF NEW.w026 IS DISTINCT FROM OLD.w026
     OR NEW.w027 IS DISTINCT FROM OLD.w027
     OR NEW.w088 IS DISTINCT FROM OLD.w088
     OR NEW.w089 IS DISTINCT FROM OLD.w089
     OR NEW.w069 IS DISTINCT FROM OLD.w069
     OR NEW.w073 IS DISTINCT FROM OLD.w073
     OR NEW.data->>'w070' IS DISTINCT FROM OLD.data->>'w070'
     OR NEW.data->>'w078' IS DISTINCT FROM OLD.data->>'w078'
     OR NEW.data->>'w090' IS DISTINCT FROM OLD.data->>'w090' THEN
    RAISE EXCEPTION 'w071 identity and w091 w092 immutable'
      USING ERRCODE = 'w012', CONSTRAINT = 'w093';
  END IF;

  IF OLD.w074 <> 'W086' THEN
    
    IF NEW.w074 IS DISTINCT FROM OLD.w074
       OR NEW.data->>'w076' IS DISTINCT FROM OLD.data->>'w076'
       OR NEW.data->>'w077' IS DISTINCT FROM OLD.data->>'w077'
       OR NEW.data->>'w087' IS DISTINCT FROM OLD.data->>'w087' THEN
      RAISE EXCEPTION 'w071 w094 w092 w095'
        USING ERRCODE = 'w012', CONSTRAINT = 'w096';
    END IF;
  ELSIF NEW.w074 NOT IN ('W086', 'W075', 'W097') THEN
    RAISE EXCEPTION 'W086 w071 w098 only w099 to W075 or W097'
      USING ERRCODE = 'w012', CONSTRAINT = 'w100';
  END IF;

  
  
  IF NEW.w074 = 'W086' THEN
    IF NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
       OR NEW.data->>'w087' IS NOT NULL THEN
      RAISE EXCEPTION 'W086 w071 w101 w102 w080 w083'
        USING ERRCODE = 'w012', CONSTRAINT = 'w103';
    END IF;
  ELSIF NEW.data->>'w076' IS NULL OR NEW.data->>'w077' IS NULL THEN
    RAISE EXCEPTION 'w104 w071 w043 w076 and w077'
      USING ERRCODE = 'w012', CONSTRAINT = 'w103';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w105() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.w106 = NEW.w107 THEN
      RAISE EXCEPTION 'assertion w009 w010 w011'
        USING ERRCODE = 'w012', CONSTRAINT = 'w108';
    END IF;
    IF NEW.w109 NOT IN ('W020', 'W021', 'W110', 'W111') THEN
      RAISE EXCEPTION 'unknown assertion w061 type %', NEW.w109
        USING ERRCODE = 'w012', CONSTRAINT = 'w112';
    END IF;
    IF NEW.w073 = 'ADMIN' THEN
      IF NEW.w074 <> 'W075'
         OR NEW.data->>'w076' IS NULL OR NEW.data->>'w077' IS NULL
         OR NEW.data->>'w078' IS NOT NULL OR NEW.w113 IS NOT NULL THEN
        RAISE EXCEPTION 'ADMIN assertion w010 w017 w079 W075 with w080 w081 and no w082 w083'
          USING ERRCODE = 'w012', CONSTRAINT = 'w114';
      END IF;
    ELSIF NEW.w073 = 'W115' THEN
      IF NEW.w074 <> 'W086'
         OR NEW.data->>'w078' IS NULL OR NEW.w113 IS NULL
         OR NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
         OR NEW.data->>'w087' IS NOT NULL THEN
        RAISE EXCEPTION 'W115 assertion w010 w017 w079 W086 with w082 w081 and no w080 w083'
          USING ERRCODE = 'w012', CONSTRAINT = 'w114';
      END IF;
    ELSIF NEW.w073 = 'W085' THEN
      IF NEW.w074 <> 'W086'
         OR NEW.data->>'w078' IS NULL OR NEW.w113 IS NOT NULL
         OR NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
         OR NEW.data->>'w087' IS NOT NULL THEN
        RAISE EXCEPTION 'W085 assertion w010 w017 w079 W086 with no w080 w083'
          USING ERRCODE = 'w012', CONSTRAINT = 'w114';
      END IF;
    ELSE
      RAISE EXCEPTION 'unknown assertion w073 %', NEW.w073
        USING ERRCODE = 'w012', CONSTRAINT = 'w114';
    END IF;
    RETURN NEW;
  END IF;

  
  IF NEW.w026 IS DISTINCT FROM OLD.w026
     OR NEW.w027 IS DISTINCT FROM OLD.w027
     OR NEW.w106 IS DISTINCT FROM OLD.w106
     OR NEW.w107 IS DISTINCT FROM OLD.w107
     OR NEW.w109 IS DISTINCT FROM OLD.w109
     OR NEW.w073 IS DISTINCT FROM OLD.w073
     OR NEW.w113 IS DISTINCT FROM OLD.w113
     OR NEW.data->>'w078' IS DISTINCT FROM OLD.data->>'w078'
     OR NEW.data->>'w090' IS DISTINCT FROM OLD.data->>'w090' THEN
    RAISE EXCEPTION 'w065 assertion identity and w091 w092 immutable'
      USING ERRCODE = 'w012', CONSTRAINT = 'w116';
  END IF;

  IF OLD.w074 <> 'W086' THEN
    
    IF NEW.w074 IS DISTINCT FROM OLD.w074
       OR NEW.data->>'w076' IS DISTINCT FROM OLD.data->>'w076'
       OR NEW.data->>'w077' IS DISTINCT FROM OLD.data->>'w077'
       OR NEW.data->>'w087' IS DISTINCT FROM OLD.data->>'w087' THEN
      RAISE EXCEPTION 'w065 assertion w094 w092 w095'
        USING ERRCODE = 'w012', CONSTRAINT = 'w117';
    END IF;
  ELSIF NEW.w074 NOT IN ('W086', 'W075', 'W097') THEN
    RAISE EXCEPTION 'W086 assertion w098 only w099 to W075 or W097'
      USING ERRCODE = 'w012', CONSTRAINT = 'w118';
  END IF;

  
  
  IF NEW.w074 = 'W086' THEN
    IF NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
       OR NEW.data->>'w087' IS NOT NULL THEN
      RAISE EXCEPTION 'W086 assertion w101 w102 w080 w083'
        USING ERRCODE = 'w012', CONSTRAINT = 'w119';
    END IF;
  ELSIF NEW.data->>'w076' IS NULL OR NEW.data->>'w077' IS NULL THEN
    RAISE EXCEPTION 'w104 assertion w043 w076 and w077'
      USING ERRCODE = 'w012', CONSTRAINT = 'w119';
  END IF;
  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION fixture.w120() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.w121 = NEW.w122 THEN
      RAISE EXCEPTION 'w123 w124 and w062 w125 w010 w011'
        USING ERRCODE = 'w012', CONSTRAINT = 'w126';
    END IF;
    IF NEW.data->'w091' IS NOT NULL
       AND jsonb_typeof(NEW.data->'w091') <> 'object' THEN
      RAISE EXCEPTION 'w123 w091 w010 w017 a jsonb object'
        USING ERRCODE = 'w012', CONSTRAINT = 'w127';
    END IF;
    IF NEW.w128 <> 'W086'
       OR NEW.data->>'w078' IS NULL OR NEW.data->>'w129' IS NULL
       OR NEW.data->'w091' IS NULL
       OR NEW.data->>'w087' IS NOT NULL OR NEW.data->>'w130' IS NOT NULL
       OR NEW.data->>'w076' IS NOT NULL OR NEW.data->>'w077' IS NOT NULL
       OR NEW.data->>'w131' IS NOT NULL OR NEW.data->>'w132' IS NOT NULL THEN
      RAISE EXCEPTION 'w133 w010 w017 w079 W086 with no w080 or w134 w083'
        USING ERRCODE = 'w012', CONSTRAINT = 'w135';
    END IF;
    RETURN NEW;
  END IF;

  
  IF NEW.w026 IS DISTINCT FROM OLD.w026
     OR NEW.w027 IS DISTINCT FROM OLD.w027
     OR NEW.w089 IS DISTINCT FROM OLD.w089
     OR NEW.w121 IS DISTINCT FROM OLD.w121
     OR NEW.w122 IS DISTINCT FROM OLD.w122
     OR NEW.data->>'w078' IS DISTINCT FROM OLD.data->>'w078'
     OR NEW.data->>'w129' IS DISTINCT FROM OLD.data->>'w129'
     OR NEW.data->>'w136' IS DISTINCT FROM OLD.data->>'w136'
     OR NEW.data->'w091' IS DISTINCT FROM OLD.data->'w091' THEN
    RAISE EXCEPTION 'w123 identity and w091 w092 immutable'
      USING ERRCODE = 'w012', CONSTRAINT = 'w137';
  END IF;

  
  IF OLD.w128 IN ('W097', 'W138') THEN
    RAISE EXCEPTION 'w095 w123 w139 (W097, W138) w092 immutable'
      USING ERRCODE = 'w012', CONSTRAINT = 'w140';
  END IF;

  
  IF OLD.w128 = 'W086' THEN
    IF NEW.w128 NOT IN ('W075', 'W097') THEN
      RAISE EXCEPTION 'W086 w123 w098 only w099 to W075 or W097'
        USING ERRCODE = 'w012', CONSTRAINT = 'w141';
    END IF;
    IF NEW.data->>'w131' IS NOT NULL OR NEW.data->>'w132' IS NOT NULL
       OR NEW.data->>'w076' IS NULL OR NEW.data->>'w077' IS NULL THEN
      RAISE EXCEPTION 'W086 w099 w043 w080 w083 and w101 set w134 w083'
        USING ERRCODE = 'w012', CONSTRAINT = 'w141';
    END IF;
  ELSIF OLD.w128 = 'W075' THEN
    IF NEW.w128 <> 'W138' THEN
      RAISE EXCEPTION 'W075 w123 w098 only w099 to W138'
        USING ERRCODE = 'w012', CONSTRAINT = 'w141';
    END IF;
    IF NEW.data->>'w076' IS DISTINCT FROM OLD.data->>'w076'
       OR NEW.data->>'w077' IS DISTINCT FROM OLD.data->>'w077'
       OR NEW.data->>'w087' IS DISTINCT FROM OLD.data->>'w087' THEN
      RAISE EXCEPTION 'w104 w083 w092 w142 on w134'
        USING ERRCODE = 'w012', CONSTRAINT = 'w143';
    END IF;
    IF NEW.data->>'w131' IS NULL OR NEW.data->>'w132' IS NULL THEN
      RAISE EXCEPTION 'W138 w123 w043 w131 and w132'
        USING ERRCODE = 'w012', CONSTRAINT = 'w144';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS fixture.w145 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w026       uuid NOT NULL,
    w146 text,
    data            jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w147
    ON fixture.w145 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w148
    ON fixture.w145 (w026, w146);
CREATE INDEX IF NOT EXISTS w149
    ON fixture.w145 (w026, (fixture.w003(data->>'w090')));

CREATE TABLE IF NOT EXISTS fixture.w025 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w026       uuid NOT NULL,
    w030           text NOT NULL DEFAULT 'W034',
    w028      text,
    w150     text NOT NULL DEFAULT 'W151',
    w152 text NOT NULL,
    w073          text NOT NULL DEFAULT 'W153',
    w154 integer NOT NULL DEFAULT 0,
    w155     text,
    w156    text GENERATED ALWAYS AS (md5(lower(btrim(data->>'content')))) STORED,
    data            jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w157
    ON fixture.w025 (w026, w027);

CREATE UNIQUE INDEX IF NOT EXISTS w158
    ON fixture.w025 (w026, w028, w156) NULLS NOT DISTINCT
    WHERE (w073 = ANY (ARRAY['W153'::text, 'W115'::text]));

CREATE UNIQUE INDEX IF NOT EXISTS w159
    ON fixture.w025 (w026, w155) WHERE (w155 IS NOT NULL);
CREATE INDEX IF NOT EXISTS w160
    ON fixture.w025 (w026, (fixture.w003(data->>'w161')) DESC, w027)
    WHERE (w152 = ANY (ARRAY['W162'::text, 'W163'::text, 'W164'::text]));
CREATE INDEX IF NOT EXISTS w165
    ON fixture.w025 (w026, (fixture.w003(data->>'w166')))
    WHERE (data->>'w166') IS NOT NULL;
CREATE INDEX IF NOT EXISTS w167
    ON fixture.w025 (w155);
CREATE INDEX IF NOT EXISTS w168
    ON fixture.w025 (w152, (fixture.w003(data->>'w161')));
CREATE INDEX IF NOT EXISTS w169
    ON fixture.w025 (w152, w154);
CREATE INDEX IF NOT EXISTS w170
    ON fixture.w025 USING gin ((data->'w046'))
    WHERE (w152 = ANY (ARRAY['W162'::text, 'W163'::text, 'W164'::text]));
CREATE INDEX IF NOT EXISTS w171
    ON fixture.w025 (w026, w152);

DROP TRIGGER IF EXISTS w172 ON fixture.w025;
CREATE TRIGGER w172 BEFORE INSERT OR UPDATE ON fixture.w025
    FOR EACH ROW EXECUTE FUNCTION fixture.w032();

CREATE TABLE IF NOT EXISTS fixture.w173 (
    w026 uuid NOT NULL,
    w174 uuid NOT NULL,
    w175  uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w176 PRIMARY KEY (w026, w174, w175),
    CONSTRAINT w177
        FOREIGN KEY (w026, w174) REFERENCES fixture.w025 (w026, w027),
    CONSTRAINT w178
        FOREIGN KEY (w026, w175) REFERENCES fixture.w145 (w026, w027)
);

CREATE INDEX IF NOT EXISTS w179
    ON fixture.w173 (w175);
CREATE INDEX IF NOT EXISTS w180
    ON fixture.w173 (w026, w174, (fixture.w003(data->>'w090')) DESC);

CREATE TABLE IF NOT EXISTS fixture.w181 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL,
    w174 uuid NOT NULL,
    data      jsonb NOT NULL
);

CREATE INDEX IF NOT EXISTS w182
    ON fixture.w181 (w026, w174);

CREATE TABLE IF NOT EXISTS fixture.w183 (
    w027           uuid PRIMARY KEY,
    w026    uuid NOT NULL,
    w030        text NOT NULL,
    w028   text,
    w184      boolean NOT NULL DEFAULT false,
    w174    uuid NOT NULL,
    w185     integer NOT NULL DEFAULT 1,
    w186 text GENERATED ALWAYS AS (lower(btrim(data->>'w053'))) STORED,
    data         jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w187
    ON fixture.w183 (w026, w174);
CREATE UNIQUE INDEX IF NOT EXISTS w188
    ON fixture.w183 (w026, w030, w028, w186) NULLS NOT DISTINCT
    WHERE (w184 = false);
CREATE INDEX IF NOT EXISTS w189
    ON fixture.w183 (w026, w186) WHERE (w184 = false);

DROP TRIGGER IF EXISTS w190 ON fixture.w183;
CREATE TRIGGER w190 BEFORE INSERT OR UPDATE ON fixture.w183
    FOR EACH ROW EXECUTE FUNCTION fixture.w051();

CREATE TABLE IF NOT EXISTS fixture.w016 (
    w027         uuid PRIMARY KEY DEFAULT uuidv7(),
    w026  uuid NOT NULL,
    w028 text,
    w006     uuid NOT NULL,
    w007     uuid NOT NULL,
    w014  text NOT NULL,
    data       jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w191
    ON fixture.w016 (w026, w006, w007, w014);
CREATE UNIQUE INDEX IF NOT EXISTS w192
    ON fixture.w016 (w026, w027);
CREATE INDEX IF NOT EXISTS w193
    ON fixture.w016 (w026, w007);

DROP TRIGGER IF EXISTS w194 ON fixture.w016;
DROP TRIGGER IF EXISTS w195 ON fixture.w016;
CREATE TRIGGER w195 BEFORE INSERT OR UPDATE ON fixture.w016
    FOR EACH ROW EXECUTE FUNCTION fixture.w005();

CREATE TABLE IF NOT EXISTS fixture.w196 (
    w026 uuid NOT NULL,
    w197   uuid NOT NULL,
    w175  uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w198 PRIMARY KEY (w026, w197, w175),
    CONSTRAINT w199
        FOREIGN KEY (w026, w197) REFERENCES fixture.w016 (w026, w027),
    CONSTRAINT w200
        FOREIGN KEY (w026, w175) REFERENCES fixture.w145 (w026, w027)
);

CREATE INDEX IF NOT EXISTS w201
    ON fixture.w196 (w175);

CREATE TABLE IF NOT EXISTS fixture.w125 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w089 text NOT NULL,
    w202   text NOT NULL,
    data        jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w203
    ON fixture.w125 (w026, w089, w202);
CREATE UNIQUE INDEX IF NOT EXISTS w204
    ON fixture.w125 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w205
    ON fixture.w125 (w026, w027, w202);
CREATE UNIQUE INDEX IF NOT EXISTS w206
    ON fixture.w125 (w026, w027, w089);
CREATE INDEX IF NOT EXISTS w207
    ON fixture.w125 (w026, (fixture.w003(data->>'w208')), w027);
CREATE INDEX IF NOT EXISTS w209
    ON fixture.w125 (w026, w202, w027);
CREATE INDEX IF NOT EXISTS w210
    ON fixture.w125 (w026, w089);

CREATE TABLE IF NOT EXISTS fixture.w211 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w088   uuid NOT NULL,
    w089 text NOT NULL,
    w069  text NOT NULL,
    w074      text NOT NULL,
    w073      text NOT NULL,
    data        jsonb NOT NULL,
    CONSTRAINT w212
        FOREIGN KEY (w026, w088, w089)
        REFERENCES fixture.w125 (w026, w027, w089) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS w213
    ON fixture.w211 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w214
    ON fixture.w211 (w026, w089, w069) WHERE (w074 <> 'W097'::text);
CREATE INDEX IF NOT EXISTS w215
    ON fixture.w211 (w026, w088);
CREATE INDEX IF NOT EXISTS w216
    ON fixture.w211 (w026, (fixture.w003(data->>'w090')), w027)
    WHERE (w074 = 'W086'::text);
CREATE INDEX IF NOT EXISTS w217
    ON fixture.w211 (w026, w089, w069) WHERE (w074 = 'W075'::text);

DROP TRIGGER IF EXISTS w218 ON fixture.w211;
CREATE TRIGGER w218 BEFORE INSERT OR UPDATE ON fixture.w211
    FOR EACH ROW EXECUTE FUNCTION fixture.w068();

CREATE TABLE IF NOT EXISTS fixture.w219 (
    w027                uuid PRIMARY KEY DEFAULT uuidv7(),
    w026         uuid NOT NULL,
    w106     uuid NOT NULL,
    w107     uuid NOT NULL,
    w109     text NOT NULL,
    w074            text NOT NULL,
    w073            text NOT NULL,
    w113 uuid,
    data              jsonb NOT NULL,
    CONSTRAINT w220
        FOREIGN KEY (w026, w106) REFERENCES fixture.w125 (w026, w027) ON DELETE CASCADE,
    CONSTRAINT w221
        FOREIGN KEY (w026, w107) REFERENCES fixture.w125 (w026, w027) ON DELETE CASCADE,
    CONSTRAINT w222
        FOREIGN KEY (w026, w113) REFERENCES fixture.w145 (w026, w027)
);

CREATE UNIQUE INDEX IF NOT EXISTS w223
    ON fixture.w219 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w224
    ON fixture.w219 (w026, w106, w109, w107)
    WHERE (w074 <> 'W097'::text);
CREATE INDEX IF NOT EXISTS w225
    ON fixture.w219 (w026, w107) WHERE (w074 = 'W075'::text);
CREATE INDEX IF NOT EXISTS w226
    ON fixture.w219 (w026, (fixture.w003(data->>'w090')), w027)
    WHERE (w074 = 'W086'::text);
CREATE INDEX IF NOT EXISTS w227
    ON fixture.w219 (w026, w106) WHERE (w074 = 'W075'::text);
CREATE INDEX IF NOT EXISTS w228
    ON fixture.w219 (w026, w113) WHERE (w113 IS NOT NULL);

DROP TRIGGER IF EXISTS w229 ON fixture.w219;
CREATE TRIGGER w229 BEFORE INSERT OR UPDATE ON fixture.w219
    FOR EACH ROW EXECUTE FUNCTION fixture.w105();

CREATE TABLE IF NOT EXISTS fixture.w230 (
    w026    uuid NOT NULL,
    w231 uuid NOT NULL,
    w175     uuid NOT NULL,
    data         jsonb NOT NULL,
    CONSTRAINT w232 PRIMARY KEY (w026, w231, w175),
    CONSTRAINT w233
        FOREIGN KEY (w026, w231) REFERENCES fixture.w219 (w026, w027) ON DELETE CASCADE,
    CONSTRAINT w234
        FOREIGN KEY (w026, w175) REFERENCES fixture.w145 (w026, w027)
);

CREATE INDEX IF NOT EXISTS w235
    ON fixture.w230 (w026, w175);

CREATE TABLE IF NOT EXISTS fixture.w236 (
    w026 uuid NOT NULL,
    w088 uuid NOT NULL,
    w174 uuid NOT NULL,
    w175  uuid NOT NULL,
    data      jsonb NOT NULL,
    CONSTRAINT w237 PRIMARY KEY (w026, w088, w174, w175),
    CONSTRAINT w238
        FOREIGN KEY (w026, w088) REFERENCES fixture.w125 (w026, w027),
    CONSTRAINT w239
        FOREIGN KEY (w026, w174) REFERENCES fixture.w025 (w026, w027),
    CONSTRAINT w240
        FOREIGN KEY (w026, w175) REFERENCES fixture.w145 (w026, w027)
);

CREATE INDEX IF NOT EXISTS w241
    ON fixture.w236 (w026, w175);
CREATE INDEX IF NOT EXISTS w242
    ON fixture.w236 (w026, w174);

CREATE TABLE IF NOT EXISTS fixture.w243 (
    w027               uuid PRIMARY KEY DEFAULT uuidv7(),
    w026        uuid NOT NULL,
    w089      text NOT NULL,
    w121 uuid NOT NULL,
    w122 uuid NOT NULL,
    w128            text NOT NULL,
    data             jsonb NOT NULL,
    CONSTRAINT w244
        FOREIGN KEY (w026, w121, w089)
        REFERENCES fixture.w125 (w026, w027, w089) ON DELETE CASCADE,
    CONSTRAINT w245
        FOREIGN KEY (w026, w122, w089)
        REFERENCES fixture.w125 (w026, w027, w089) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS w246
    ON fixture.w243 (w026, w027);

CREATE UNIQUE INDEX IF NOT EXISTS w247
    ON fixture.w243 (w026, w121)
    WHERE (w128 = ANY (ARRAY['W086'::text, 'W075'::text]));
CREATE INDEX IF NOT EXISTS w248
    ON fixture.w243 (w026, w121) WHERE (w128 = 'W075'::text);
CREATE INDEX IF NOT EXISTS w249
    ON fixture.w243 (w026, w122) WHERE (w128 = 'W075'::text);
CREATE INDEX IF NOT EXISTS w250
    ON fixture.w243 (w026, (fixture.w003(data->>'w129')), w027)
    WHERE (w128 = 'W086'::text);

DROP TRIGGER IF EXISTS w251 ON fixture.w243;
CREATE TRIGGER w251 BEFORE INSERT OR UPDATE ON fixture.w243
    FOR EACH ROW EXECUTE FUNCTION fixture.w120();

CREATE TABLE IF NOT EXISTS fixture.w252 (
    w026     uuid NOT NULL,
    w106 uuid NOT NULL,
    w064 text NOT NULL,
    w109 text NOT NULL,
    w060   text NOT NULL,
    w174     uuid NOT NULL,
    w175      uuid NOT NULL,
    data          jsonb NOT NULL,
    CONSTRAINT w253 PRIMARY KEY (w026, w106, w109, w060, w174, w175),
    CONSTRAINT w254
        FOREIGN KEY (w026, w106, w064)
        REFERENCES fixture.w125 (w026, w027, w202),
    CONSTRAINT w255
        FOREIGN KEY (w026, w106, w174, w175)
        REFERENCES fixture.w236 (w026, w088, w174, w175) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS w256
    ON fixture.w252 (w026, w060, w106, w174, w175);
CREATE INDEX IF NOT EXISTS w257
    ON fixture.w252 (w026, w174);

DROP TRIGGER IF EXISTS w258 ON fixture.w252;
CREATE TRIGGER w258 BEFORE INSERT OR UPDATE ON fixture.w252
    FOR EACH ROW EXECUTE FUNCTION fixture.w059();

CREATE TABLE IF NOT EXISTS fixture.w259 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w028  text,
    w260 text NOT NULL,
    data        jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w261
    ON fixture.w259 (w026, w260);
CREATE INDEX IF NOT EXISTS w262
    ON fixture.w259 (w026, w028);

CREATE TABLE IF NOT EXISTS fixture.w263 (
    w026   uuid PRIMARY KEY,
    w264 integer NOT NULL DEFAULT 0,
    data        jsonb NOT NULL
);

CREATE TABLE IF NOT EXISTS fixture.w265 (
    w027              uuid PRIMARY KEY DEFAULT uuidv7(),
    w026       uuid NOT NULL,
    type            text NOT NULL,
    w074          text NOT NULL,
    w266   integer NOT NULL DEFAULT 0,
    w267     uuid,
    w146 text,
    data            jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w268
    ON fixture.w265 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w269
    ON fixture.w265 (w026, w146);
CREATE INDEX IF NOT EXISTS w270
    ON fixture.w265 (w026, ((data->'w271'->>'w175'))) WHERE (type = 'w272-w002-w082'::text);
CREATE INDEX IF NOT EXISTS w273
    ON fixture.w265 ((fixture.w003(data->>'w274'))) WHERE (w074 = 'W275'::text);
CREATE INDEX IF NOT EXISTS w276
    ON fixture.w265 ((fixture.w003(data->>'w277'))) WHERE (w074 = 'W278'::text);
CREATE INDEX IF NOT EXISTS w279
    ON fixture.w265 (w026);

CREATE TABLE IF NOT EXISTS fixture.w280 (
    w026  uuid NOT NULL,
    w028 text NOT NULL,
    w074     text NOT NULL,
    data       jsonb NOT NULL,
    CONSTRAINT w281 PRIMARY KEY (w026, w028)
);

CREATE TABLE IF NOT EXISTS fixture.w282 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL,
    w174 uuid NOT NULL,
    data      jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w283
    ON fixture.w282 (w026, w027);
CREATE UNIQUE INDEX IF NOT EXISTS w284
    ON fixture.w282 (w026, w174);

CREATE TABLE IF NOT EXISTS fixture.w285 (
    w026     uuid NOT NULL,
    w286 uuid NOT NULL,
    w175      uuid NOT NULL,
    data          jsonb NOT NULL,
    CONSTRAINT w287 PRIMARY KEY (w026, w286, w175),
    CONSTRAINT w288
        FOREIGN KEY (w026, w286) REFERENCES fixture.w282 (w026, w027),
    CONSTRAINT w289
        FOREIGN KEY (w026, w175) REFERENCES fixture.w145 (w026, w027)
);

CREATE INDEX IF NOT EXISTS w290
    ON fixture.w285 (w175);

CREATE TABLE IF NOT EXISTS fixture.w291 (
    w027          uuid PRIMARY KEY DEFAULT uuidv7(),
    w026   uuid NOT NULL,
    w174   uuid NOT NULL,
    w292  uuid NOT NULL,
    w293 uuid NOT NULL,
    w294 uuid NOT NULL,
    data        jsonb NOT NULL,
    CONSTRAINT w295
        FOREIGN KEY (w026, w174) REFERENCES fixture.w025 (w026, w027)
);

CREATE UNIQUE INDEX IF NOT EXISTS w296
    ON fixture.w291 (w026, w294);
CREATE INDEX IF NOT EXISTS w297
    ON fixture.w291 (w026, w174);

CREATE TABLE IF NOT EXISTS fixture.w298 (
    w027        uuid PRIMARY KEY DEFAULT uuidv7(),
    w026 uuid NOT NULL,
    w074    text NOT NULL,
    data      jsonb NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS w299
    ON fixture.w298 (w026) WHERE (w074 = 'W300'::text);
CREATE INDEX IF NOT EXISTS w301
    ON fixture.w298 (w026, (fixture.w003(data->>'w302')) DESC);

CREATE OR REPLACE VIEW fixture.w303 AS
SELECT w027,
       w026,
       data->'w271'                                 AS w271,
       w146,
       data->>'w304'                               AS w304,
       data->>'w305'                         AS w305,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w145;

CREATE OR REPLACE VIEW fixture.w306 AS
SELECT w027,
       w026,
       data->>'content'                                AS content,
       data->>'name'                                   AS name,
       w030,
       w028,
       w150,
       (SELECT COALESCE(array_agg(s), '{}'::text[])
        FROM jsonb_array_elements_text(data->'w046') AS s) AS w046,
       data->>'w307'                             AS w307,
       w152,
       w154,
       fixture.w003(data->>'w308') AS w308,
       fixture.w003(data->>'w090')         AS w090,
       fixture.w003(data->>'w161')         AS w161,
       w073,
       (data->>'w309')::boolean                      AS w309,
       fixture.w003(data->>'w038')      AS w038,
       fixture.w003(data->>'w037')     AS w037,
       fixture.w003(data->>'w042')         AS w042,
       fixture.w003(data->>'w041')        AS w041,
       fixture.w003(data->>'w310')        AS w310,
       data->>'w311'                            AS w311,
       w155,
       fixture.w003(data->>'w312')       AS w312,
       fixture.w003(data->>'w166') AS w166,
       fixture.w003(data->>'w313')   AS w313,
       fixture.w003(data->>'w314')   AS w314,
       w156
FROM fixture.w025;

CREATE OR REPLACE VIEW fixture.w315 AS
SELECT w026,
       w174,
       w175,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w173;

CREATE OR REPLACE VIEW fixture.w316 AS
SELECT w027,
       w026,
       w174,
       data->>'w317'                            AS w317,
       fixture.w003(data->>'w318') AS w318
FROM fixture.w181;

CREATE OR REPLACE VIEW fixture.w319 AS
SELECT w027,
       w026,
       data->>'w053'                               AS w053,
       w186,
       data->>'digest'                                AS digest,
       w030,
       w028,
       data->>'w150'                           AS w150,
       (SELECT COALESCE(array_agg(s), '{}'::text[])
        FROM jsonb_array_elements_text(data->'w046') AS s) AS w046,
       (data->>'w309')::boolean                     AS w309,
       w184,
       w174,
       w185,
       data->>'w307'                            AS w307,
       fixture.w003(data->>'w090') AS w090,
       fixture.w003(data->>'w161') AS w161
FROM fixture.w183;

CREATE OR REPLACE VIEW fixture.w320 AS
SELECT w027,
       w026,
       w028,
       w006,
       w007,
       w014,
       (data->>'w023')::float8                  AS w023,
       data->>'w307'                            AS w307,
       data->>'w073'                                AS w073,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w016;

CREATE OR REPLACE VIEW fixture.w321 AS
SELECT w026,
       w197,
       w175,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w196;

CREATE OR REPLACE VIEW fixture.w322 AS
SELECT w027,
       w026,
       w089,
       data->>'name'                                  AS name,
       w202,
       fixture.w003(data->>'w323') AS w323,
       fixture.w003(data->>'w208')  AS w208,
       fixture.w003(data->>'w090')    AS w090
FROM fixture.w125;

CREATE OR REPLACE VIEW fixture.w324 AS
SELECT w027,
       w026,
       w088,
       w089,
       data->>'w070'                            AS w070,
       w069,
       w074,
       w073,
       data->>'w078'                           AS w078,
       data->>'w076'                            AS w076,
       fixture.w003(data->>'w077') AS w077,
       data->>'w087'                       AS w087,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w211;

CREATE OR REPLACE VIEW fixture.w325 AS
SELECT w027,
       w026,
       w106,
       w107,
       w109,
       w074,
       w073,
       data->>'w078'                           AS w078,
       w113,
       data->>'w076'                            AS w076,
       fixture.w003(data->>'w077') AS w077,
       data->>'w087'                       AS w087,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w219;

CREATE OR REPLACE VIEW fixture.w326 AS
SELECT w026,
       w231,
       w175,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w230;

CREATE OR REPLACE VIEW fixture.w327 AS
SELECT w026,
       w088,
       w174,
       w175,
       fixture.w003(data->>'w328')    AS w328,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w236;

CREATE OR REPLACE VIEW fixture.w329 AS
SELECT w027,
       w026,
       w089,
       w121,
       w122,
       w128,
       data->>'w078'                           AS w078,
       fixture.w003(data->>'w129') AS w129,
       data->>'w076'                            AS w076,
       fixture.w003(data->>'w077')  AS w077,
       data->>'w131'                           AS w131,
       fixture.w003(data->>'w132') AS w132,
       data->>'w136'                                AS w136,
       data->>'w087'                       AS w087,
       data->>'w130'                       AS w130,
       data->'w091'                             AS w091
FROM fixture.w243;

CREATE OR REPLACE VIEW fixture.w330 AS
SELECT w026,
       w106,
       w064,
       w109,
       data->>'w331'                            AS w331,
       w060,
       w174,
       w175,
       fixture.w003(data->>'w328')    AS w328,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w252;

CREATE OR REPLACE VIEW fixture.w332 AS
SELECT w027,
       w026,
       w028,
       w260,
       fixture.w003(data->>'w333') AS w333
FROM fixture.w259;

CREATE OR REPLACE VIEW fixture.w334 AS
SELECT w026,
       w264,
       fixture.w003(data->>'w335')        AS w335,
       fixture.w003(data->>'w336') AS w336,
       fixture.w003(data->>'w337')     AS w337
FROM fixture.w263;

CREATE OR REPLACE VIEW fixture.w338 AS
SELECT w027,
       w026,
       type,
       data->'w271'                                AS w271,
       w074,
       w266,
       fixture.w003(data->>'w277')     AS w277,
       w267,
       fixture.w003(data->>'w274') AS w274,
       data->>'w339'                            AS w339,
       data->'w340'                                 AS w340,
       w146,
       data->>'w341'                            AS w341,
       fixture.w003(data->>'w090')       AS w090,
       fixture.w003(data->>'w161')       AS w161,
       fixture.w003(data->>'w342')   AS w342
FROM fixture.w265;

CREATE OR REPLACE VIEW fixture.w343 AS
SELECT w026,
       w028,
       w074,
       fixture.w003(data->>'w302') AS w302,
       fixture.w003(data->>'w337') AS w337,
       data->>'w344'                                 AS w344
FROM fixture.w280;

CREATE OR REPLACE VIEW fixture.w345 AS
SELECT w027,
       w026,
       w174,
       fixture.w003(data->>'w346') AS w346,
       data->>'w347'                            AS w347,
       data->>'w073'                                AS w073,
       data->>'w136'                                AS w136,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w282;

CREATE OR REPLACE VIEW fixture.w348 AS
SELECT w026,
       w286,
       w175,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w285;

CREATE OR REPLACE VIEW fixture.w349 AS
SELECT w027,
       w026,
       w292,
       w174,
       w293,
       w294,
       fixture.w003(data->>'w090') AS w090
FROM fixture.w291;

CREATE OR REPLACE VIEW fixture.w350 AS
SELECT w027,
       w026,
       fixture.w003(data->>'w302') AS w302,
       fixture.w003(data->>'w337') AS w337,
       w074,
       data->>'w344'                                 AS w344
FROM fixture.w298;

SELECT pg_advisory_unlock(hashtext('w001.w002.schema'));
