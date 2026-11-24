-- PostgreSQL catalog queries (spec A.5), shared by the Java and TypeScript
-- dialects. Sections are delimited by `-- name: <query>` markers; `?` is a
-- positional parameter, rewritten to the engine's bind syntax by the loader.

-- name: extension_owned
SELECT d.objid FROM pg_depend d
WHERE d.refclassid = 'pg_extension'::regclass AND d.deptype = 'e'
  AND CASE d.classid
      WHEN 'pg_class'::regclass THEN EXISTS (SELECT 1 FROM pg_class o
                                             WHERE o.oid = d.objid
                                               AND o.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))
      WHEN 'pg_proc'::regclass THEN EXISTS (SELECT 1 FROM pg_proc o
                                            WHERE o.oid = d.objid
                                              AND o.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))
      WHEN 'pg_type'::regclass THEN EXISTS (SELECT 1 FROM pg_type o
                                            WHERE o.oid = d.objid
                                              AND o.typnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))
      ELSE true END

-- name: range_types
SELECT t.oid, t.typname, r.rngmultitypid
FROM pg_range r
JOIN pg_type t ON t.oid = r.rngtypid
JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ?

-- name: auto_type_procs
SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
FROM pg_depend d
JOIN pg_type t ON t.oid = d.refobjid
JOIN pg_namespace n ON n.oid = t.typnamespace
LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
WHERE n.nspname = ? AND t.typtype IN ('r','m')
  AND d.classid = 'pg_proc'::regclass
  AND d.refclassid = 'pg_type'::regclass
  AND d.deptype = 'i'

-- name: auto_type_casts
SELECT d.objid, coalesce(rg.rngtypid, d.refobjid) AS rng
FROM pg_depend d
JOIN pg_type t ON t.oid = d.refobjid
JOIN pg_namespace n ON n.oid = t.typnamespace
LEFT JOIN pg_range rg ON rg.rngmultitypid = d.refobjid
WHERE n.nspname = ? AND t.typtype IN ('r','m')
  AND d.classid = 'pg_cast'::regclass
  AND d.refclassid = 'pg_type'::regclass
  AND d.deptype = 'i'

-- name: relations
SELECT c.oid, c.relname, c.relkind, c.relpersistence,
       c.reloptions::text AS options, c.relreplident,
       c.relrowsecurity, c.relforcerowsecurity,
       pg_get_partkeydef(c.oid) AS partkey,
       pg_get_expr(c.relpartbound, c.oid) AS partbound,
       (SELECT p.relname FROM pg_inherits i
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE i.inhrelid = c.oid AND p.relkind = 'p'
         LIMIT 1) AS pparent,
       (SELECT string_agg(p.relkind, '') FROM pg_inherits i
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE i.inhrelid = c.oid) AS parentkinds,
       (SELECT ic.relname FROM pg_index ix JOIN pg_class ic ON ic.oid = ix.indexrelid
         WHERE ix.indrelid = c.oid AND ix.indisreplident) AS replindex,
       (SELECT t.oid FROM pg_type t
         WHERE t.typrelid = c.oid) AS typeoid,
       s.srvname AS ftserver, f.ftoptions::text AS ftoptions
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_foreign_table f ON f.ftrelid = c.oid
LEFT JOIN pg_foreign_server s ON s.oid = f.ftserver
WHERE n.nspname = ? AND c.relkind IN ('r','v','m','f','p','c','t','I','S')
ORDER BY c.oid

-- name: columns
SELECT c.relname AS parent, c.oid AS reloid, a.attnum,
       a.attname, format_type(a.atttypid, a.atttypmod) AS typ,
       a.attgenerated, a.attcollation, t.typcollation, a.attidentity,
       a.attstorage, t.typstorage, a.attcompression,
       CASE WHEN a.attcollation <> 0 AND a.attcollation <> t.typcollation
            THEN a.attcollation::regcollation::text ELSE NULL END AS collation,
       (a.attnotnull OR EXISTS (
           SELECT 1 FROM pg_constraint cn
           WHERE cn.conrelid = a.attrelid AND cn.contype = 'n'
             AND a.attnum = ANY(cn.conkey))) AS notnull,
       pg_get_expr(d.adbin, d.adrelid) AS defexpr,
       format_type(ids.seqtypid, NULL) AS idtype,
       ids.seqincrement, ids.seqmin, ids.seqmax,
       ids.seqstart, ids.seqcache, ids.seqcycle
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid AND c.relkind IN ('r', 'p', 'f')
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_type t ON t.oid = a.atttypid
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
LEFT JOIN LATERAL (
    SELECT ps.* FROM pg_depend dep
    JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
    JOIN pg_sequence ps ON ps.seqrelid = sq.oid
    WHERE dep.classid = 'pg_class'::regclass
      AND dep.refclassid = 'pg_class'::regclass
      AND dep.refobjid = a.attrelid
      AND dep.refobjsubid = a.attnum
      AND dep.deptype = 'i'
) ids ON true
WHERE n.nspname = ? AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY c.oid, a.attnum

-- name: constraints
SELECT c.oid, c.conname, c.contype, rel.relname AS parent,
       pg_get_constraintdef(c.oid) AS def,
       ref.oid AS refoid, c.conindid,
       EXISTS (SELECT 1 FROM pg_index ix
                WHERE ix.indexrelid = c.conindid AND ix.indisreplident
                  AND c.contype IN ('p','u')) AS replident
FROM pg_constraint c
JOIN pg_class rel ON rel.oid = c.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
LEFT JOIN pg_class ref ON ref.oid = c.confrelid
WHERE n.nspname = ? AND c.contype IN ('p','u','f','c','x')
  AND c.conislocal
ORDER BY c.oid

-- name: indexes
SELECT i.indexrelid AS oid, ic.relname, tc.relname AS parent,
       pg_get_indexdef(i.indexrelid) AS def, i.indisreplident AS replident,
       i.indisvalid AS valid
FROM pg_index i
JOIN pg_class ic ON ic.oid = i.indexrelid
JOIN pg_class tc ON tc.oid = i.indrelid
JOIN pg_namespace n ON n.oid = ic.relnamespace
WHERE n.nspname = ? AND ic.relkind = 'i'
  AND NOT EXISTS (SELECT 1 FROM pg_constraint co
                  WHERE co.conindid = i.indexrelid
                    AND co.contype IN ('p','u','x'))
  AND NOT EXISTS (SELECT 1 FROM pg_inherits inh WHERE inh.inhrelid = i.indexrelid)
ORDER BY i.indexrelid

-- name: views
SELECT c.oid, c.relname, c.relkind, c.relpersistence, c.relispopulated,
       pg_get_viewdef(c.oid, true) AS def,
       c.reloptions::text AS options,
       (SELECT string_agg(a.attname || ' ' || format_type(a.atttypid, a.atttypmod),
                          ', ' ORDER BY a.attnum)
          FROM pg_attribute a
         WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) AS cols
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ? AND c.relkind IN ('v','m')
ORDER BY c.oid

-- name: sequences
SELECT c.oid, c.relname, c.relpersistence,
       format_type(s.seqtypid, NULL) AS stype,
       s.seqincrement, s.seqmin, s.seqmax, s.seqstart, s.seqcache, s.seqcycle
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_sequence s ON s.seqrelid = c.oid
WHERE n.nspname = ? AND c.relkind = 'S'
  AND NOT EXISTS (
      SELECT 1 FROM pg_depend d
      WHERE d.classid = 'pg_class'::regclass
        AND d.refclassid = 'pg_class'::regclass
        AND d.objid = c.oid AND d.deptype = 'i')
ORDER BY c.oid

-- name: sequence_owners
SELECT dep.objid AS seq_oid, dep.refobjid AS tbl_oid,
       rc.relname AS tbl, ra.attname AS col,
       rn.nspname AS tblns
FROM pg_depend dep
JOIN pg_class sq ON sq.oid = dep.objid AND sq.relkind = 'S'
JOIN pg_namespace sn ON sn.oid = sq.relnamespace
JOIN pg_class rc ON rc.oid = dep.refobjid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
JOIN pg_attribute ra ON ra.attrelid = dep.refobjid AND ra.attnum = dep.refobjsubid
WHERE dep.classid = 'pg_class'::regclass
  AND dep.refclassid = 'pg_class'::regclass
  AND dep.deptype = 'a' AND sn.nspname = ?

-- name: routines
SELECT p.oid, p.proname, p.prokind,
       coalesce((SELECT string_agg(format_type(a, NULL), ', '
                                 ORDER BY x.i)
                   FROM unnest(string_to_array(p.proargtypes::text, ' ')::oid[])
                        WITH ORDINALITY AS x(a, i)), '') AS sigargs,
       (CASE WHEN p.proretset THEN 'SETOF ' ELSE '' END)
           || format_type(p.prorettype, NULL) AS rettype,
       pg_get_functiondef(p.oid) AS def
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = ? AND p.prokind IN ('f','p','w')
ORDER BY p.oid

-- name: aggregates
SELECT p.oid, p.proname,
       pg_get_function_identity_arguments(p.oid) AS idargs,
       pg_get_function_arguments(p.oid) AS args,
       a.aggkind,
       a.aggtransfn::regprocedure::text AS sfunc,
       CASE WHEN a.aggtranstype = 0 THEN 'internal'
            ELSE format_type(a.aggtranstype, NULL) END AS stype,
       NULLIF(a.aggtransspace, 0) AS sspace,
       NULLIF(a.aggfinalfn, 0)::regprocedure::text AS ffunc,
       a.aggfinalextra, a.aggfinalmodify,
       NULLIF(a.aggcombinefn, 0)::regprocedure::text AS combine,
       NULLIF(a.aggserialfn, 0)::regprocedure::text AS serial,
       NULLIF(a.aggdeserialfn, 0)::regprocedure::text AS deserial,
       a.agginitval,
       NULLIF(a.aggmtransfn, 0)::regprocedure::text AS msfunc,
       NULLIF(a.aggminvtransfn, 0)::regprocedure::text AS minv,
       CASE WHEN a.aggmtransfn::text = '-' THEN NULL
            WHEN a.aggmtranstype = 0 THEN 'internal'
            ELSE format_type(a.aggmtranstype, NULL) END AS mstype,
       NULLIF(a.aggmtransspace, 0) AS msspace,
       NULLIF(a.aggmfinalfn, 0)::regprocedure::text AS mffunc,
       a.aggmfinalextra, a.aggmfinalmodify,
       a.aggminitval,
       NULLIF(a.aggsortop, 0)::regoper::text AS sortop
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
JOIN pg_aggregate a ON a.aggfnoid = p.oid
WHERE n.nspname = ? AND p.prokind = 'a'
ORDER BY p.oid

-- name: triggers
SELECT t.oid, t.tgname, c.relname AS parent, t.tgenabled,
       pg_get_triggerdef(t.oid, true) AS def, t.tgfoid
FROM pg_trigger t
JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ? AND NOT t.tgisinternal AND t.tgparentid = 0
ORDER BY t.oid

-- name: enums
SELECT t.oid, t.typname,
       (SELECT array_agg(e.enumlabel ORDER BY e.enumsortorder, e.oid)
          FROM pg_enum e WHERE e.enumtypid = t.oid) AS labels
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typtype = 'e'
ORDER BY t.oid

-- name: domain_checks
SELECT c.contypid AS dom, c.conname, pg_get_constraintdef(c.oid) AS def
FROM pg_constraint c
JOIN pg_type t ON t.oid = c.contypid
JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
WHERE c.contype = 'c'
ORDER BY c.contypid, c.conname

-- name: domains
SELECT t.oid, t.typname,
       format_type(t.typbasetype, t.typtypmod) AS basetype,
       t.typnotnull, pg_get_expr(t.typdefaultbin, 0) AS dflt,
       NULLIF(t.typcollation, 0)::regcollation::text AS coll,
       t.typbasetype
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typtype = 'd'
ORDER BY t.oid

-- name: composites
SELECT t.oid, t.typname, t.typrelid,
       (SELECT string_agg(quote_ident(a.attname) || ' '
                || format_type(a.atttypid, a.atttypmod)
                || CASE WHEN a.attcollation <> at.typcollation
                        THEN ' collate '
                             || a.attcollation::regcollation::text
                        ELSE '' END,
                ', ' ORDER BY a.attnum)
          FROM pg_attribute a
          JOIN pg_type at ON at.oid = a.atttypid
          WHERE a.attrelid = t.typrelid
            AND a.attnum > 0 AND NOT a.attisdropped) AS attrs
FROM pg_type t
JOIN pg_class c ON c.oid = t.typrelid AND c.relkind = 'c'
JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typtype = 'c'
ORDER BY t.oid

-- name: ranges
SELECT t.oid, t.typname,
       format_type(r.rngsubtype, NULL) AS subtype,
       NULLIF(r.rngcollation, 0)::regcollation::text AS coll,
       -- SUBTYPE_OPCLASS takes a bare name and pg_range
       -- always stores the resolved class, so it is
       -- reported only when it is not the subtype's
       -- default btree operator class
       CASE WHEN EXISTS (
                SELECT 1 FROM pg_opclass d
                JOIN pg_opfamily f
                  ON f.oid = d.opcfamily
                JOIN pg_am am
                  ON am.oid = f.opfmethod
                WHERE am.amname = 'btree'
                  AND d.opcintype = r.rngsubtype
                  AND d.opcdefault
                  AND d.oid = r.rngsubopc)
            THEN NULL
            ELSE (SELECT o.opcname FROM pg_opclass o
                  WHERE o.oid = r.rngsubopc)
       END AS opc,
       NULLIF(r.rngcanonical, 0)::regproc::text AS canonical,
       NULLIF(r.rngsubdiff, 0)::regproc::text AS diff
FROM pg_range r
JOIN pg_type t ON t.oid = r.rngtypid
JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ?
ORDER BY t.oid

-- name: policies
SELECT po.oid, po.polname, po.polrelid, c.relname, po.polcmd,
       po.polpermissive,
       (SELECT array_agg(x ORDER BY x)
          FROM (SELECT DISTINCT CASE WHEN u = 0 THEN 'PUBLIC'
                                     ELSE r.rolname END AS x
                  FROM unnest(po.polroles) u
                  LEFT JOIN pg_roles r ON r.oid = u) s) AS roles,
       pg_get_expr(po.polqual, po.polrelid) AS using,
       pg_get_expr(po.polwithcheck, po.polrelid) AS wcheck
FROM pg_policy po
JOIN pg_class c ON c.oid = po.polrelid
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
ORDER BY po.oid

-- name: statistics
SELECT s.oid, s.stxname, s.stxrelid,
       pg_get_statisticsobjdef(s.oid) AS def
FROM pg_statistic_ext s
JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
ORDER BY s.oid

-- name: rules
SELECT r.oid, r.rulename, c.relname AS parent, r.ev_class,
       r.ev_enabled, pg_get_ruledef(r.oid, true) AS def
FROM pg_rewrite r
JOIN pg_class c ON c.oid = r.ev_class
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
WHERE r.rulename <> '_RETURN'
ORDER BY r.oid

-- name: collations
SELECT cl.oid, cl.collname, cl.collprovider, cl.collisdeterministic,
       cl.collencoding, cl.collcollate, cl.collctype,
       COALESCE(to_json(cl)->>'colllocale',
                to_json(cl)->>'colliculocale') AS colllocale,
       to_jsonb(cl)->>'collicurules' AS collicurules
FROM pg_collation cl
JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = ?
WHERE cl.collprovider IN ('c', 'i')
  AND cl.collencoding IN (-1,
      (SELECT encoding FROM pg_database
       WHERE datname = current_database()))
ORDER BY cl.oid

-- name: conversions
SELECT cv.oid, cv.conname,
       pg_encoding_to_char(cv.conforencoding) AS forenc,
       pg_encoding_to_char(cv.contoencoding) AS toenc,
       cv.conproc::regproc::text AS procname,
       cv.conproc::oid AS conproc
FROM pg_conversion cv
JOIN pg_namespace n ON n.oid = cv.connamespace AND n.nspname = ?
WHERE NOT cv.condefault
ORDER BY cv.oid

-- name: ts_templates
SELECT t.oid, t.tmplname,
       t.tmplinit::regproc::text AS init,
       t.tmpllexize::regproc::text AS lexize,
       t.tmplinit::oid AS init_oid,
       t.tmpllexize::oid AS lexize_oid
FROM pg_ts_template t
JOIN pg_namespace n ON n.oid = t.tmplnamespace
WHERE n.nspname = ?
ORDER BY t.oid

-- name: ts_parsers
SELECT p.oid, p.prsname,
       p.prsstart::regproc::text AS start,
       p.prstoken::regproc::text AS token,
       p.prsend::regproc::text AS end,
       p.prsheadline::regproc::text AS headline,
       p.prslextype::regproc::text AS lextype,
       p.prsstart::oid AS start_oid,
       p.prstoken::oid AS token_oid,
       p.prsend::oid AS end_oid,
       p.prsheadline::oid AS headline_oid,
       p.prslextype::oid AS lextype_oid
FROM pg_ts_parser p
JOIN pg_namespace n ON n.oid = p.prsnamespace
WHERE n.nspname = ?
ORDER BY p.oid

-- name: ts_dictionaries
SELECT d.oid, d.dictname, d.dicttemplate,
       tn.nspname || '.' || t.tmplname AS tmplref,
       d.dictinitoption
FROM pg_ts_dict d
JOIN pg_ts_template t ON t.oid = d.dicttemplate
JOIN pg_namespace tn ON tn.oid = t.tmplnamespace
JOIN pg_namespace n ON n.oid = d.dictnamespace
WHERE n.nspname = ?
ORDER BY d.oid

-- name: ts_configurations
SELECT c.oid, c.cfgname, c.cfgparser,
       pn.nspname || '.' || p.prsname AS prsref
FROM pg_ts_config c
JOIN pg_ts_parser p ON p.oid = c.cfgparser
JOIN pg_namespace pn ON pn.oid = p.prsnamespace
JOIN pg_namespace n ON n.oid = c.cfgnamespace
WHERE n.nspname = ?
ORDER BY c.oid

-- name: ts_config_maps
SELECT m.maptokentype, m.mapseqno, m.mapdict,
       dn.nspname || '.' || d.dictname AS dictref,
       tt.alias
FROM pg_ts_config_map m
JOIN pg_ts_config c ON c.oid = m.mapcfg
JOIN pg_ts_dict d ON d.oid = m.mapdict
JOIN pg_namespace dn ON dn.oid = d.dictnamespace
LEFT JOIN LATERAL (
    SELECT alias FROM ts_token_type(c.cfgparser)
    WHERE tokid = m.maptokentype
) tt ON true
WHERE m.mapcfg = ?
ORDER BY m.mapseqno

-- name: operators
SELECT o.oid, o.oprname, o.oprleft, o.oprright,
       o.oprleft::regtype::text AS leftt,
       o.oprright::regtype::text AS rightt,
       o.oprresult::regtype::text AS result,
       o.oprcode::regproc::text AS proc,
       o.oprcode::oid AS proc_oid,
       o.oprrest::regproc::text AS rest,
       o.oprrest::oid AS rest_oid,
       o.oprjoin::regproc::text AS joinsel,
       o.oprjoin::oid AS join_oid,
       cn.nspname || '.' || co.oprname AS comref,
       nn.nspname || '.' || no.oprname AS negref,
       o.oprcom, o.oprnegate,
       o.oprresult AS result_oid,
       o.oprcanhash, o.oprcanmerge
FROM pg_operator o
JOIN pg_namespace n ON n.oid = o.oprnamespace
LEFT JOIN pg_operator co ON co.oid = o.oprcom
LEFT JOIN pg_namespace cn ON cn.oid = co.oprnamespace
LEFT JOIN pg_operator no ON no.oid = o.oprnegate
LEFT JOIN pg_namespace nn ON nn.oid = no.oprnamespace
WHERE n.nspname = ?
ORDER BY o.oid

-- name: opfamilies
SELECT f.oid, f.opfname, a.amname
FROM pg_opfamily f
JOIN pg_am a ON a.oid = f.opfmethod
JOIN pg_namespace n ON n.oid = f.opfnamespace
WHERE n.nspname = ?
ORDER BY f.oid

-- name: opclasses
SELECT c.oid, c.opcname, a.amname, c.opcfamily,
       fn.nspname || '.' || f.opfname AS famref,
       format_type(c.opcintype, NULL) AS intype,
       format_type(c.opckeytype, NULL) AS storage,
       c.opcintype, c.opckeytype, c.opcdefault
FROM pg_opclass c
JOIN pg_am a ON a.oid = c.opcmethod
JOIN pg_opfamily f ON f.oid = c.opcfamily
JOIN pg_namespace fn ON fn.oid = f.opfnamespace
JOIN pg_namespace n ON n.oid = c.opcnamespace
WHERE n.nspname = ?
ORDER BY c.oid

-- name: opclass_amop_members
SELECT m.amopstrategy, m.amoppurpose,
       m.amopopr::regoperator::text AS opref,
       m.amopopr::oid AS amopopr_oid,
       sn.nspname || '.' || sf.opfname AS sortref,
       m.amopsortfamily
FROM pg_amop m
LEFT JOIN pg_opfamily sf ON sf.oid = m.amopsortfamily
LEFT JOIN pg_namespace sn ON sn.oid = sf.opfnamespace
WHERE m.amopfamily = ? AND m.amoplefttype = ?
ORDER BY m.amopstrategy, m.amoprighttype, m.oid

-- name: opclass_amproc_members
SELECT m.amprocnum,
       format_type(m.amproclefttype, NULL) AS lt,
       format_type(m.amprocrighttype, NULL) AS rt,
       m.amproc::regprocedure::text AS fn,
       m.amproc::oid AS amproc_oid
FROM pg_amproc m
WHERE m.amprocfamily = ? AND m.amproclefttype = ?
ORDER BY m.amprocnum, m.oid
