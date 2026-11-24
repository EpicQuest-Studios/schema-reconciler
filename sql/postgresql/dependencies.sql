-- Dependency-edge loaders (spec 5.8, AGENTS.md edge inventory) and the
-- external-dependent scan. `?` is a positional parameter.

-- name: dep_view_class
SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
FROM pg_rewrite r
JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
    AND d.refclassid = 'pg_class'::regclass
JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = ?
JOIN pg_class rc ON rc.oid = d.refobjid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname = ?
WHERE r.rulename = '_RETURN' AND rc.oid <> v.oid

-- name: dep_view_other
SELECT DISTINCT v.oid AS viewer, d.refobjid AS dep
FROM pg_rewrite r
JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
    AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
JOIN pg_class v ON v.oid = r.ev_class AND v.relkind IN ('v','m')
JOIN pg_namespace vn ON vn.oid = v.relnamespace AND vn.nspname = ?
WHERE r.rulename = '_RETURN'

-- name: dep_routine
SELECT DISTINCT d.objid AS caller, d.refobjid AS dep
FROM pg_depend d
JOIN pg_proc p ON p.oid = d.objid
JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = ?
WHERE d.classid = 'pg_proc'::regclass
  AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                       'pg_type'::regclass, 'pg_language'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_index_proc
SELECT DISTINCT i.indexrelid AS idx, d.refobjid AS dep
FROM pg_depend d
JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
JOIN pg_index i ON i.indexrelid = ic.oid
JOIN pg_namespace n ON n.oid = ic.relnamespace AND n.nspname = ?
JOIN pg_proc p ON p.oid = d.refobjid
JOIN pg_namespace pn ON pn.oid = p.pronamespace AND pn.nspname = ?
WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_proc'::regclass

-- name: dep_attrdef
SELECT ad.adrelid AS tbl, a.attname AS col, d.refobjid AS dep
FROM pg_depend d
JOIN pg_attrdef ad ON ad.oid = d.objid
JOIN pg_class tc ON tc.oid = ad.adrelid
JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = ?
JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
    AND NOT a.attisdropped
WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
  AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                       'pg_type'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_constraint
SELECT d.objid AS con, d.refclassid::regclass::text AS refcls,
       d.refobjid AS dep
FROM pg_depend d
JOIN pg_constraint co ON co.oid = d.objid
JOIN pg_class cr ON cr.oid = co.conrelid
JOIN pg_namespace cn ON cn.oid = cr.relnamespace AND cn.nspname = ?
WHERE d.classid = 'pg_constraint'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                       'pg_class'::regclass, 'pg_constraint'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_type
SELECT d.objid AS dom, d.refobjid AS dep
FROM pg_depend d
JOIN pg_type t ON t.oid = d.objid AND t.typtype IN ('d','r')
JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
WHERE d.classid = 'pg_type'::regclass
  AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)
UNION
SELECT c.contypid, d.refobjid
FROM pg_depend d
JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
JOIN pg_type t ON t.oid = c.contypid
JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = ?
WHERE d.classid = 'pg_constraint'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                       'pg_class'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_policy
SELECT d.objid AS pol, d.refobjid AS dep
FROM pg_depend d
JOIN pg_policy po ON po.oid = d.objid
JOIN pg_class c ON c.oid = po.polrelid
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = ?
WHERE d.classid = 'pg_policy'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                       'pg_class'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_column_type
SELECT d.objid AS tbl, d.objsubid AS attnum, a.attname AS col,
       d.refobjid AS dep
FROM pg_depend d
JOIN pg_class tc ON tc.oid = d.objid
JOIN pg_namespace tn ON tn.oid = tc.relnamespace AND tn.nspname = ?
LEFT JOIN pg_attribute a ON a.attrelid = d.objid AND a.attnum = d.objsubid
    AND NOT a.attisdropped
WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
  AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass,'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,'pg_opfamily'::regclass)

-- name: dep_partition
SELECT d.objid AS child, d.refobjid AS dep
FROM pg_depend d
JOIN pg_class cc ON cc.oid = d.objid
JOIN pg_namespace cn ON cn.oid = cc.relnamespace AND cn.nspname = ?
WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
  AND d.deptype IN ('a', 'P')

-- name: dep_other
SELECT d.classid::regclass::text AS cls, d.objid,
       d.refclassid::regclass::text AS refcls, d.refobjid AS dep
FROM pg_depend d
WHERE d.classid IN ('pg_type'::regclass,'pg_language'::regclass,
                    'pg_rewrite'::regclass,'pg_statistic_ext'::regclass,
                    'pg_cast'::regclass,'pg_transform'::regclass,
                    'pg_conversion'::regclass,'pg_operator'::regclass,
                    'pg_opclass'::regclass,'pg_opfamily'::regclass,
                    'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                    'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
  AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,
                       'pg_type'::regclass,'pg_collation'::regclass,
                       'pg_language'::regclass,'pg_constraint'::regclass,
                       'pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass)

-- name: external_dependents
-- every arm selects (refobjid, depns, depname): our object and the
-- foreign-namespace dependent that pins it (spec 5.8.3)
SELECT DISTINCT d.refobjid AS tgt, rn.nspname AS depns, rc.relname AS depname
FROM pg_depend d
JOIN pg_rewrite r ON r.oid = d.objid
JOIN pg_class rc ON rc.oid = r.ev_class
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
JOIN pg_class tgt ON tgt.oid = d.refobjid
JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
WHERE d.classid = 'pg_rewrite'::regclass AND d.refclassid = 'pg_class'::regclass
  AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
FROM pg_depend d
JOIN pg_constraint co ON co.oid = d.objid
JOIN pg_class rc ON rc.oid = co.conrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
JOIN pg_class tgt ON tgt.oid = d.refobjid
JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
WHERE d.classid = 'pg_constraint'::regclass AND d.refclassid = 'pg_class'::regclass
  AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, sn.nspname, sq.relname
FROM pg_depend d
JOIN pg_class sq ON sq.oid = d.objid AND sq.relkind = 'S'
JOIN pg_namespace sn ON sn.oid = sq.relnamespace
JOIN pg_class tgt ON tgt.oid = d.refobjid
JOIN pg_namespace tn ON tn.oid = tgt.relnamespace AND tn.nspname = ?
WHERE d.classid = 'pg_class'::regclass AND d.refclassid = 'pg_class'::regclass
  AND d.deptype = 'a' AND sn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, pn.nspname, p.proname
FROM pg_depend d
JOIN pg_proc p ON p.oid = d.objid
JOIN pg_namespace pn ON pn.oid = p.pronamespace
WHERE d.classid = 'pg_proc'::regclass
  AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                       'pg_type'::regclass, 'pg_language'::regclass)
  AND pn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
FROM pg_depend d
JOIN pg_rewrite r ON r.oid = d.objid
JOIN pg_class rc ON rc.oid = r.ev_class
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
WHERE d.classid = 'pg_rewrite'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
  AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || t.tgname
FROM pg_depend d
JOIN pg_trigger t ON t.oid = d.objid
JOIN pg_class rc ON rc.oid = t.tgrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
WHERE d.classid = 'pg_trigger'::regclass
  AND d.refclassid = 'pg_proc'::regclass AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, rc.relname || '.' || a.attname
FROM pg_depend d
JOIN pg_attrdef ad ON ad.oid = d.objid
JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
    AND NOT a.attisdropped
JOIN pg_class rc ON rc.oid = ad.adrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
  AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass,
                       'pg_type'::regclass)
  AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, co.conname
FROM pg_depend d
JOIN pg_constraint co ON co.oid = d.objid
JOIN pg_class rc ON rc.oid = co.conrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
WHERE d.classid = 'pg_constraint'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
  AND rn.nspname <> ?
UNION
SELECT DISTINCT d.refobjid, rn.nspname, ic.relname
FROM pg_depend d
JOIN pg_class ic ON ic.oid = d.objid AND ic.relkind = 'i'
JOIN pg_namespace rn ON rn.oid = ic.relnamespace
WHERE d.classid = 'pg_class'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass)
  AND rn.nspname <> ?
UNION
-- columns (objsubid > 0) and typed tables (objsubid = 0) in
-- other namespaces typed by or collated with our objects
SELECT DISTINCT d.refobjid, rn.nspname,
       rc.relname || CASE WHEN a.attname IS NULL THEN ''
                          ELSE '.' || a.attname END
FROM pg_depend d
JOIN pg_class rc ON rc.oid = d.objid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
LEFT JOIN pg_attribute a ON a.attrelid = d.objid
    AND a.attnum = d.objsubid AND a.attnum > 0
    AND NOT a.attisdropped
WHERE d.classid = 'pg_class'::regclass AND d.deptype = 'n'
  AND d.refclassid IN ('pg_type'::regclass, 'pg_collation'::regclass)
UNION
-- types (domains, ranges, base types) in other namespaces
-- built on ours or calling our routines in defaults
SELECT DISTINCT d.refobjid, tn.nspname, t.typname
FROM pg_depend d
JOIN pg_type t ON t.oid = d.objid
JOIN pg_namespace tn ON tn.oid = t.typnamespace AND tn.nspname <> ?
WHERE d.classid = 'pg_type'::regclass
  AND d.refclassid IN ('pg_type'::regclass, 'pg_proc'::regclass,
                       'pg_collation'::regclass)
UNION
-- check constraints on other namespaces' domains whose
-- expressions use our objects
SELECT DISTINCT d.refobjid, dn.nspname,
       dt.typname || '.' || c.conname
FROM pg_depend d
JOIN pg_constraint c ON c.oid = d.objid AND c.contypid <> 0
JOIN pg_type dt ON dt.oid = c.contypid
JOIN pg_namespace dn ON dn.oid = dt.typnamespace AND dn.nspname <> ?
WHERE d.classid = 'pg_constraint'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                       'pg_class'::regclass)
UNION
-- policies on other namespaces' tables whose expressions
-- use our objects
SELECT DISTINCT d.refobjid, rn.nspname,
       rc.relname || '.' || po.polname
FROM pg_depend d
JOIN pg_policy po ON po.oid = d.objid
JOIN pg_class rc ON rc.oid = po.polrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
WHERE d.classid = 'pg_policy'::regclass
  AND d.refclassid IN ('pg_proc'::regclass, 'pg_type'::regclass,
                       'pg_class'::regclass)
UNION
-- dependents in other namespaces on our text-search and
-- operator objects — views, column defaults, and the
-- families' own member rows (an external dictionary on our
-- template, an external family member on our operator);
-- no tgt join: byOid resolves in-namespace refs only
SELECT DISTINCT d.refobjid, rn.nspname, rc.relname
FROM pg_depend d
JOIN pg_rewrite r ON r.oid = d.objid
JOIN pg_class rc ON rc.oid = r.ev_class
JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
WHERE d.classid = 'pg_rewrite'::regclass
  AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,
                       'pg_opfamily'::regclass)
UNION
SELECT DISTINCT d.refobjid, rn.nspname,
       rc.relname || '.' || a.attname
FROM pg_depend d
JOIN pg_attrdef ad ON ad.oid = d.objid
JOIN pg_attribute a ON a.attrelid = ad.adrelid AND a.attnum = ad.adnum
    AND NOT a.attisdropped
JOIN pg_class rc ON rc.oid = ad.adrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace AND rn.nspname <> ?
WHERE d.classid = 'pg_attrdef'::regclass AND d.deptype = 'n'
  AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass,
                       'pg_operator'::regclass,'pg_opclass'::regclass,
                       'pg_opfamily'::regclass)
UNION
SELECT DISTINCT d.refobjid, tdn.nspname, td.dictname
FROM pg_depend d
JOIN pg_ts_dict td ON td.oid = d.objid
JOIN pg_namespace tdn ON tdn.oid = td.dictnamespace AND tdn.nspname <> ?
WHERE d.classid = 'pg_ts_dict'::regclass
  AND d.refclassid IN ('pg_ts_config'::regclass,'pg_ts_dict'::regclass,
                       'pg_ts_parser'::regclass,'pg_ts_template'::regclass)
UNION
-- config mappings record as pg_ts_config -> pg_ts_dict
SELECT DISTINCT d.refobjid, cn.nspname, c.cfgname
FROM pg_depend d
JOIN pg_ts_config c ON c.oid = d.objid
JOIN pg_namespace cn ON cn.oid = c.cfgnamespace AND cn.nspname <> ?
WHERE d.classid = 'pg_ts_config'::regclass
  AND d.refclassid IN ('pg_ts_dict'::regclass,'pg_ts_parser'::regclass)
UNION
SELECT DISTINCT d.refobjid, ocn.nspname, oc.opcname
FROM pg_depend d
JOIN pg_opclass oc ON oc.oid = d.objid
JOIN pg_namespace ocn ON ocn.oid = oc.opcnamespace AND ocn.nspname <> ?
WHERE d.classid = 'pg_opclass'::regclass
  AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass,
                       'pg_type'::regclass)
UNION
SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
FROM pg_depend d
JOIN pg_amop m ON m.oid = d.objid
JOIN pg_opfamily f ON f.oid = m.amopfamily
JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> ?
WHERE d.classid = 'pg_amop'::regclass
  AND d.refclassid IN ('pg_opfamily'::regclass,'pg_operator'::regclass)
UNION
SELECT DISTINCT d.refobjid, fn.nspname, f.opfname
FROM pg_depend d
JOIN pg_amproc m ON m.oid = d.objid
JOIN pg_opfamily f ON f.oid = m.amprocfamily
JOIN pg_namespace fn ON fn.oid = f.opfnamespace AND fn.nspname <> ?
WHERE d.classid = 'pg_amproc'::regclass
  AND d.refclassid IN ('pg_opfamily'::regclass,'pg_proc'::regclass)
UNION
SELECT DISTINCT d.refobjid, ons.nspname, o.oprname
FROM pg_depend d
JOIN pg_operator o ON o.oid = d.objid
JOIN pg_namespace ons ON ons.oid = o.oprnamespace AND ons.nspname <> ?
WHERE d.classid = 'pg_operator'::regclass
  AND d.refclassid IN ('pg_operator'::regclass,'pg_proc'::regclass,
                       'pg_type'::regclass)
UNION
SELECT DISTINCT d.refobjid, ofn.nspname, of.opfname
FROM pg_depend d
JOIN pg_opfamily of ON of.oid = d.objid
JOIN pg_namespace ofn ON ofn.oid = of.opfnamespace AND ofn.nspname <> ?
WHERE d.classid = 'pg_opfamily'::regclass
  AND d.refclassid = 'pg_opfamily'::regclass
UNION
-- casts not anchored to this namespace (by function or by
-- either type — the loadOther anchor rule) that still use
-- our types or routines
SELECT DISTINCT d.refobjid,
       coalesce(pn.nspname, sn.nspname, dtn.nspname, '-'),
       'cast(' || ca.castsource::regtype::text || ' AS '
              || ca.casttarget::regtype::text || ')'
FROM pg_depend d
JOIN pg_cast ca ON ca.oid = d.objid
LEFT JOIN pg_proc p ON p.oid = ca.castfunc
LEFT JOIN pg_namespace pn ON pn.oid = p.pronamespace
JOIN pg_type st ON st.oid = ca.castsource
JOIN pg_namespace sn ON sn.oid = st.typnamespace
JOIN pg_type tt ON tt.oid = ca.casttarget
JOIN pg_namespace dtn ON dtn.oid = tt.typnamespace
WHERE d.classid = 'pg_cast'::regclass
  AND pn.nspname IS DISTINCT FROM ?
  AND sn.nspname IS DISTINCT FROM ?
  AND dtn.nspname IS DISTINCT FROM ?
