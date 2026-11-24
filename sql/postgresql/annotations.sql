-- Annotation loaders (spec A.5): comments, security labels, privileges,
-- default privileges, ownership, publication bindings, casts, transforms
-- and languages. `?` is a positional parameter.
--
-- The seclabel and comment queries embed the NS_OBJECTS union inline:
-- every branch resolves a namespace-scoped catalog row to
-- (classid, objoid, display-name) and takes the namespace parameter, so
-- the queries take 17 identical parameters in branch order.
-- The ACL fragments render an acl column as a deterministic sorted
-- grantee:PRIVILEGE[*] token list (PUBLIC for grantee 0, <owner> for the
-- object's owner, * for grantable; the grantor is dropped — who made a
-- grant is provenance, not desired state).

-- name: seclabels
SELECT o.name AS target, d.objoid, o.cls::text AS cls, d.objsubid,
       a.attname, d.provider, d.label
FROM pg_seclabel d
JOIN (
        SELECT 'pg_class'::regclass AS cls, c.oid AS obj, c.relname AS name
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_proc', p.oid,
                p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_type', t.oid, t.typname
        FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_constraint', c.oid, rel.relname || '.' || c.conname
        FROM pg_constraint c JOIN pg_class rel ON rel.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_rewrite', r.oid, c.relname || '.' || r.rulename
        FROM pg_rewrite r JOIN pg_class c ON c.oid = r.ev_class
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_namespace', n.oid, 'namespace'
        FROM pg_namespace n WHERE n.nspname = ?
        UNION ALL SELECT 'pg_policy', p.oid, c.relname || '.' || p.polname
        FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_collation', cl.oid, cl.collname
        FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_statistic_ext', s.oid, s.stxname
        FROM pg_statistic_ext s JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_config', g.oid, g.cfgname
        FROM pg_ts_config g JOIN pg_namespace n ON n.oid = g.cfgnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_dict', g.oid, g.dictname
        FROM pg_ts_dict g JOIN pg_namespace n ON n.oid = g.dictnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_parser', g.oid, g.prsname
        FROM pg_ts_parser g JOIN pg_namespace n ON n.oid = g.prsnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_template', g.oid, g.tmplname
        FROM pg_ts_template g JOIN pg_namespace n ON n.oid = g.tmplnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_operator', g.oid, g.oprname
        FROM pg_operator g JOIN pg_namespace n ON n.oid = g.oprnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_opclass', g.oid, g.opcname
        FROM pg_opclass g JOIN pg_namespace n ON n.oid = g.opcnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_opfamily', g.oid, g.opfname
        FROM pg_opfamily g JOIN pg_namespace n ON n.oid = g.opfnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_trigger', t.oid, c.relname || '.' || t.tgname
        FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
) o ON o.cls = d.classoid AND o.obj = d.objoid
LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
        AND a.attrelid = d.objoid AND a.attnum = d.objsubid
ORDER BY 1, 2, 3

-- name: privileges
SELECT 'pg_class' AS cls, 'privileges:' || c.relname AS name,
       c.oid, '' AS attname,
       (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = c.relowner THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(c.relacl) a) toks)
       AS acl
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ? AND c.relacl IS NOT NULL
UNION ALL
SELECT 'pg_proc', 'privileges:' || p.proname || '('
        || pg_get_function_identity_arguments(p.oid) || ')',
        p.oid, '',
       (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = p.proowner THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(p.proacl) a) toks)
       AS acl
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = ? AND p.proacl IS NOT NULL
UNION ALL
SELECT 'pg_type', 'privileges:' || t.typname, t.oid, '',
       (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = t.typowner THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(t.typacl) a) toks)
       AS acl
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typacl IS NOT NULL
UNION ALL
SELECT 'pg_attribute', 'privileges:' || c.relname
        || '.' || a.attname, a.attrelid, a.attname,
       (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = c.relowner THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(a.attacl) a) toks)
       AS acl
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ? AND a.attacl IS NOT NULL
  AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY 2, 1

-- name: default_acl
SELECT d.oid, r.rolname AS role, d.defaclobjtype AS objtype,
       (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = d.defaclrole THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(d.defaclacl) a) toks)
       AS acl
FROM pg_default_acl d
JOIN pg_roles r ON r.oid = d.defaclrole
JOIN pg_namespace n ON n.oid = d.defaclnamespace
WHERE n.nspname = ?
ORDER BY 2, 3

-- name: ownership
-- an index's relowner always follows its table's — it
-- cannot diverge — and a composite's 'c' or a toast 't'
-- row follows its type/table, so 'i'/'I'/'c'/'t' are out
SELECT 'pg_class' AS cls, 'ownership:' || c.relname AS name,
       c.oid, c.relowner::regrole::text AS owner
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = ? AND c.relkind NOT IN ('i', 'I', 'c', 't')
  AND c.relowner::regrole::text <> current_user
UNION ALL
SELECT 'pg_proc', 'ownership:' || p.proname || '('
        || pg_get_function_identity_arguments(p.oid) || ')',
       p.oid, p.proowner::regrole::text
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = ? AND p.proowner::regrole::text <> current_user
UNION ALL
SELECT 'pg_type', 'ownership:' || t.typname,
       t.oid, t.typowner::regrole::text
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typowner::regrole::text <> current_user
  -- a table's implicit rowtype and its array type flip
  -- owner with the table — neither is a type object of
  -- its own; only a standalone composite's 'c' relation
  -- counts. A multirange follows its range (ALTER TYPE on
  -- it is rejected outright), so 'm' is out too
  AND t.typtype <> 'm'
  AND t.typelem = 0
  AND NOT EXISTS (SELECT 1 FROM pg_class rt
                  WHERE rt.oid = t.typrelid
                    AND rt.relkind <> 'c')
ORDER BY 2

-- name: namespace_owner
-- the reconcile unit's own owner — the same "don't care" rule as the
-- rel/proc/typ legs of 'ownership': only loaded when the owner differs
-- from the connecting role, so a script that assigns no owner leaves
-- the schema session-owned and reports nothing
SELECT pg_get_userbyid(n.nspowner) AS owner
FROM pg_namespace n
WHERE n.nspname = ?
  AND n.nspowner <> (SELECT oid FROM pg_roles WHERE rolname = current_user)

-- name: namespace_acl
-- the reconcile unit's own grants — NULL means the default acl
-- ({owner=UC/owner,=U/owner}), which a script declaring nothing leaves
-- in place; same grantee:<owner>/PUBLIC normalization as 'privileges'
SELECT (SELECT string_agg(tok, ',' ORDER BY tok) FROM (SELECT DISTINCT CASE WHEN a.grantee = 0 THEN 'PUBLIC' WHEN a.grantee = n.nspowner THEN '<owner>' ELSE a.grantee::regrole::text END || ':' || a.privilege_type || CASE WHEN a.is_grantable THEN '*' ELSE '' END AS tok FROM aclexplode(n.nspacl) a) toks)
       AS acl
FROM pg_namespace n
WHERE n.nspname = ? AND n.nspacl IS NOT NULL

-- name: publication_rel
-- PostgreSQL 15+ binding row incl. the row filter and column list
SELECT p.pubname, c.relname, c.oid,
       pg_get_expr(pr.prqual, pr.prrelid) AS qual,
       (SELECT string_agg(a.attname, ',' ORDER BY u.ord)
          FROM unnest(string_to_array(pr.prattrs::text, ' ')::smallint[])
               WITH ORDINALITY u(attnum, ord)
          JOIN pg_attribute a
            ON a.attrelid = pr.prrelid AND a.attnum = u.attnum) AS cols
FROM pg_publication_rel pr
JOIN pg_class c ON c.oid = pr.prrelid
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
JOIN pg_publication p ON p.oid = pr.prpubid
ORDER BY 1, 2

-- name: publication_rel_pre15
SELECT p.pubname, c.relname, c.oid,
       NULL AS qual, NULL AS cols
FROM pg_publication_rel pr
JOIN pg_class c ON c.oid = pr.prrelid
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
JOIN pg_publication p ON p.oid = pr.prpubid
ORDER BY 1, 2

-- name: publication_namespace
SELECT p.pubname, NULL AS relname,
       NULL AS qual, NULL AS cols
FROM pg_publication_namespace pn
JOIN pg_namespace n ON n.oid = pn.pnnspid AND n.nspname = ?
JOIN pg_publication p ON p.oid = pn.pnpubid
ORDER BY 1

-- name: publication_sequences
SELECT v.pubname, v.sequencename AS relname,
       NULL AS qual, NULL AS cols
FROM pg_publication_sequences v
JOIN pg_publication p ON p.pubname = v.pubname
 WHERE v.schemaname = ? AND NOT p.puballsequences
ORDER BY 1

-- name: casts
SELECT c.oid, c.castsource, c.casttarget, c.castfunc,
       'cast:' || format_type(c.castsource, NULL)
        || '->' || format_type(c.casttarget, NULL) AS name,
       format_type(c.castsource, NULL)
        || '->' || format_type(c.casttarget, NULL) AS bare,
       c.castcontext::text || ' ' || c.castmethod::text || ' '
           || CASE WHEN c.castfunc = 0 THEN 'none'
                   ELSE c.castfunc::regprocedure::text
                   END AS canon,
       'CREATE CAST (' || format_type(c.castsource, NULL)
           || ' AS ' || format_type(c.casttarget, NULL) || ') '
           || CASE c.castmethod
                  WHEN 'f' THEN 'WITH FUNCTION '
                          || c.castfunc::regprocedure::text
                  WHEN 'i' THEN 'WITH INOUT' END
           || CASE c.castcontext
                  WHEN 'a' THEN ' AS ASSIGNMENT'
                  WHEN 'i' THEN ' AS IMPLICIT'
                  ELSE '' END AS create_sql,
       'DROP CAST (' || format_type(c.castsource, NULL)
           || ' AS ' || format_type(c.casttarget, NULL)
           || ')' AS drop_sql
FROM pg_cast c
WHERE EXISTS (SELECT 1 FROM pg_type t
              JOIN pg_namespace n ON n.oid = t.typnamespace
              WHERE n.nspname = ?
                AND t.oid IN (c.castsource, c.casttarget))
   OR EXISTS (SELECT 1 FROM pg_proc f
              JOIN pg_namespace n ON n.oid = f.pronamespace
              WHERE n.nspname = ? AND f.oid = c.castfunc)
ORDER BY c.oid

-- name: transforms
SELECT t.oid, t.trftype, t.trffromsql, t.trftosql,
       'transform:' || format_type(t.trftype, NULL)
        || '/' || l.lanname AS name,
       format_type(t.trftype, NULL)
        || '/' || l.lanname AS bare,
       CASE WHEN t.trffromsql = 0 THEN 'none'
            ELSE t.trffromsql::regprocedure::text END || ' '
        || CASE WHEN t.trftosql = 0 THEN 'none'
                ELSE t.trftosql::regprocedure::text
                END AS canon,
       'CREATE TRANSFORM FOR '
        || format_type(t.trftype, NULL) || ' LANGUAGE '
        || quote_ident(l.lanname)
        || ' ('
        || CASE WHEN t.trffromsql = 0 THEN ''
                ELSE 'FROM SQL WITH FUNCTION '
                     || t.trffromsql::regprocedure::text
                     || CASE WHEN t.trftosql <> 0
                             THEN ', ' ELSE '' END
                END
        || CASE WHEN t.trftosql = 0 THEN ''
                ELSE 'TO SQL WITH FUNCTION '
                     || t.trftosql::regprocedure::text
                END
        || ')' AS create_sql,
       'DROP TRANSFORM FOR '
        || format_type(t.trftype, NULL) || ' LANGUAGE '
        || quote_ident(l.lanname) AS drop_sql
FROM pg_transform t JOIN pg_language l ON l.oid = t.trflang
WHERE EXISTS (SELECT 1 FROM pg_type ty
              JOIN pg_namespace n ON n.oid = ty.typnamespace
              WHERE n.nspname = ? AND ty.oid = t.trftype)
   OR EXISTS (SELECT 1 FROM pg_proc f
              JOIN pg_namespace n ON n.oid = f.pronamespace
              WHERE n.nspname = ?
                AND f.oid IN (t.trffromsql, t.trftosql))
ORDER BY t.oid

-- name: languages
SELECT l.oid, 'language:' || l.lanname AS name
FROM pg_language l
WHERE EXISTS (SELECT 1 FROM pg_proc h
              JOIN pg_namespace n ON n.oid = h.pronamespace
              WHERE n.nspname = ?
                AND h.oid IN (l.lanplcallfoid, l.laninline,
                              l.lanvalidator))
ORDER BY l.oid

-- name: comments
SELECT o.cls::text AS cls, d.objoid, d.objsubid,
       o.name AS target, a.attname, d.description
FROM pg_description d
JOIN (
        SELECT 'pg_class'::regclass AS cls, c.oid AS obj, c.relname AS name
        FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_proc', p.oid,
                p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'
        FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_type', t.oid, t.typname
        FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_constraint', c.oid, rel.relname || '.' || c.conname
        FROM pg_constraint c JOIN pg_class rel ON rel.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = rel.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_rewrite', r.oid, c.relname || '.' || r.rulename
        FROM pg_rewrite r JOIN pg_class c ON c.oid = r.ev_class
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_namespace', n.oid, 'namespace'
        FROM pg_namespace n WHERE n.nspname = ?
        UNION ALL SELECT 'pg_policy', p.oid, c.relname || '.' || p.polname
        FROM pg_policy p JOIN pg_class c ON c.oid = p.polrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_collation', cl.oid, cl.collname
        FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_statistic_ext', s.oid, s.stxname
        FROM pg_statistic_ext s JOIN pg_namespace n ON n.oid = s.stxnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_config', g.oid, g.cfgname
        FROM pg_ts_config g JOIN pg_namespace n ON n.oid = g.cfgnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_dict', g.oid, g.dictname
        FROM pg_ts_dict g JOIN pg_namespace n ON n.oid = g.dictnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_parser', g.oid, g.prsname
        FROM pg_ts_parser g JOIN pg_namespace n ON n.oid = g.prsnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_ts_template', g.oid, g.tmplname
        FROM pg_ts_template g JOIN pg_namespace n ON n.oid = g.tmplnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_operator', g.oid, g.oprname
        FROM pg_operator g JOIN pg_namespace n ON n.oid = g.oprnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_opclass', g.oid, g.opcname
        FROM pg_opclass g JOIN pg_namespace n ON n.oid = g.opcnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_opfamily', g.oid, g.opfname
        FROM pg_opfamily g JOIN pg_namespace n ON n.oid = g.opfnamespace AND n.nspname = ?
        UNION ALL SELECT 'pg_trigger', t.oid, c.relname || '.' || t.tgname
        FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
        JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
) o ON o.cls = d.classoid AND o.obj = d.objoid
LEFT JOIN pg_attribute a ON d.classoid = 'pg_class'::regclass
        AND a.attrelid = d.objoid AND a.attnum = d.objsubid
ORDER BY 1, 2, 3

-- name: other_base_types
SELECT t.oid, t.typname, t.typtype
FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE n.nspname = ? AND t.typtype = 'b' AND t.typelem = 0
ORDER BY t.oid

-- name: other_extensions
SELECT e.oid, e.extname
FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
WHERE n.nspname = ?
ORDER BY e.oid

-- name: other_default_conversions
SELECT cv.oid, cv.conname
FROM pg_conversion cv JOIN pg_namespace n ON n.oid = cv.connamespace
WHERE n.nspname = ? AND cv.condefault
ORDER BY cv.oid

-- name: other_constraints
SELECT c.oid, rel.relname AS parent, c.conname
FROM pg_constraint c
JOIN pg_class rel ON rel.oid = c.conrelid
JOIN pg_namespace n ON n.oid = rel.relnamespace
WHERE n.nspname = ? AND c.conislocal
  AND c.contype NOT IN ('p','u','f','c','x','n')
  AND NOT EXISTS (SELECT 1 FROM pg_trigger t
                  WHERE t.tgconstraint = c.oid)
ORDER BY c.oid

-- name: other_collations
SELECT cl.oid, cl.collname
FROM pg_collation cl JOIN pg_namespace n ON n.oid = cl.collnamespace
WHERE n.nspname = ?
  AND (cl.collprovider NOT IN ('c', 'i')
       OR cl.collencoding NOT IN (-1,
           (SELECT encoding FROM pg_database
            WHERE datname = current_database())))
ORDER BY cl.oid
