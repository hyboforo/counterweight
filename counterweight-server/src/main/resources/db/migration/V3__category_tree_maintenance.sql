-- ═══════════════════════════════════════════════════════════════════════════
--  Counterweight V3 — the category tree maintains its own path
--
--  `path` is an ltree and Hibernate has no mapping for it: binding a String
--  parameter yields varchar, which PostgreSQL will not implicitly cast, exactly
--  as happened with the jsonb audit columns.
--
--  Rather than fight that with casts scattered through the persistence layer,
--  the database owns the column outright. The application writes only
--  parent_id and code; the path is derived. That also removes a whole class of
--  bug — an application cannot corrupt the tree by writing an inconsistent
--  path, because it never writes one.
-- ═══════════════════════════════════════════════════════════════════════════

-- Only ltree labels are legal in a path: letters, digits and underscore.
-- Enforced on the code itself so a bad category code fails at insert with a
-- clear message rather than deep inside the path trigger.
ALTER TABLE category
    ADD CONSTRAINT category_code_is_ltree_label
    CHECK (code ~ '^[A-Za-z0-9_]{1,60}$');

CREATE OR REPLACE FUNCTION category_path() RETURNS TRIGGER AS $$
DECLARE
    v_parent_path LTREE;
BEGIN
    -- Checked here rather than relying on the CHECK constraint: constraints are
    -- evaluated after BEFORE triggers, so text2ltree would blow up first with
    -- "ltree syntax error at character 9", which tells the caller nothing.
    IF NEW.code !~ '^[A-Za-z0-9_]{1,60}$' THEN
        RAISE EXCEPTION 'category code % is not usable in a tree path', NEW.code
            USING HINT = 'Use letters, digits and underscore only, e.g. AGRO_CHEM.';
    END IF;

    IF NEW.parent_id IS NULL THEN
        NEW.path := text2ltree(lower(NEW.code));
    ELSE
        SELECT path INTO v_parent_path FROM category WHERE id = NEW.parent_id;
        IF v_parent_path IS NULL THEN
            RAISE EXCEPTION 'parent category % does not exist', NEW.parent_id;
        END IF;
        NEW.path := v_parent_path || text2ltree(lower(NEW.code));
    END IF;

    -- A category cannot be its own ancestor. Without this a cycle is possible
    -- and every subtree query then runs forever.
    IF TG_OP = 'UPDATE' AND NEW.parent_id IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM category
             WHERE id = NEW.parent_id AND path <@ OLD.path
        ) THEN
            RAISE EXCEPTION 'cannot move a category beneath its own descendant'
                USING HINT = 'That would create a cycle in the category tree.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_category_path
    BEFORE INSERT OR UPDATE OF parent_id, code ON category
    FOR EACH ROW EXECUTE FUNCTION category_path();

/* Moving or renaming a category has to carry its whole subtree with it,
   otherwise every descendant keeps a path pointing at where the parent used
   to be and drops out of subtree queries silently. */
CREATE OR REPLACE FUNCTION category_path_cascade() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.path IS DISTINCT FROM OLD.path THEN
        UPDATE category
           SET path = NEW.path || subpath(path, nlevel(OLD.path))
         WHERE path <@ OLD.path
           AND id <> NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

/* AFTER UPDATE with no column list, deliberately.
   `AFTER UPDATE OF path` looks right and does not work: `UPDATE OF` matches the
   columns named in the statement, not the ones a BEFORE trigger changed. The
   application updates parent_id, so the path column is never in the SET list
   and the cascade never fired — descendants kept pointing at where the parent
   used to be and silently dropped out of every subtree query. The
   IS DISTINCT FROM guard inside the function makes the wider scope free. */
CREATE TRIGGER trg_category_path_cascade
    AFTER UPDATE ON category
    FOR EACH ROW EXECUTE FUNCTION category_path_cascade();

-- V1 seeded the two roots with hand-written paths. Re-derive them so the
-- trigger is the single source of truth from here on.
UPDATE category SET code = code;

-- Products are looked up by category subtree constantly; this is the index
-- that makes "everything under agro" cheap.
CREATE INDEX IF NOT EXISTS idx_product_category_active
    ON product (category_id) WHERE is_active;
