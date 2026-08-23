import type { AttributeView } from "../api/types";
import { Callout, Field, inputClass } from "../ui/components";

/**
 * The per-category fields, as a form.
 *
 * §6.1 is the reason this file exists: a new product category is data entry,
 * not a migration, so nothing here may know what a hazard band or an EPA
 * number is. The form is built from what the category declares — inherited
 * declarations included, which is why a product filed three levels under
 * `agro` still shows the fields the root declares.
 *
 * Built from the declarations rather than from what the product happens to
 * carry, so a field added to the category after the product was created shows
 * up empty and fillable instead of silently missing. That distinction is not
 * academic: `requires_buyer_record` was missing from every product until V15
 * backfilled it, and a form driven by stored keys would never have offered it.
 */

/** What the form holds while it is being typed: text, except BOOL which is a real flag. */
export type AttributeDraft = Record<string, string | boolean>;

/** Seeds a draft from what the product carries, one entry per declared field. */
export function draftFrom(
  declarations: AttributeView[],
  stored: Record<string, unknown> = {},
): AttributeDraft {
  const draft: AttributeDraft = {};
  declarations.forEach((d) => {
    const value = stored[d.key];
    if (d.dataType === "BOOL") {
      draft[d.key] = value === true || value === "true";
    } else {
      draft[d.key] = value === null || value === undefined ? "" : String(value);
    }
  });
  return draft;
}

/**
 * The document to send.
 *
 * A blank optional field is left out rather than sent as an empty string: the
 * validator treats blank as absent anyway, and an absent key is what a filter
 * looking for "products with no expiry set" can find. A blank *required* field
 * is also left out, so the refusal comes back from the server naming the field
 * rather than from a rule written twice.
 */
export function toPayload(
  declarations: AttributeView[],
  draft: AttributeDraft,
): Record<string, unknown> {
  const payload: Record<string, unknown> = {};
  declarations.forEach((d) => {
    const value = draft[d.key];
    if (d.dataType === "BOOL") {
      payload[d.key] = value === true;
      return;
    }
    const text = typeof value === "string" ? value.trim() : "";
    if (text !== "") payload[d.key] = text;
  });
  return payload;
}

/** Keys the product carries that its category no longer declares. */
export function orphanedKeys(
  declarations: AttributeView[],
  stored: Record<string, unknown> = {},
): string[] {
  const declared = new Set(declarations.map((d) => d.key));
  return Object.keys(stored).filter((k) => !declared.has(k));
}

export function AttributeFields({
  declarations,
  draft,
  errors,
  onChange,
}: {
  declarations: AttributeView[];
  draft: AttributeDraft;
  errors: Record<string, string>;
  onChange: (key: string, value: string | boolean) => void;
}) {
  if (declarations.length === 0) {
    return (
      <p className="m-0 text-[13px] text-inksoft">
        This category declares no fields of its own.
      </p>
    );
  }

  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {declarations.map((d) => {
        const error = errors[`attributes.${d.key}`];
        const label = d.required ? `${d.label} *` : d.label;
        const value = draft[d.key];

        if (d.dataType === "BOOL") {
          return (
            <label
              key={d.key}
              className="flex items-center gap-2.5 text-[13.5px] cursor-pointer self-end pb-2.5"
            >
              <input
                type="checkbox"
                checked={value === true}
                onChange={(e) => onChange(d.key, e.target.checked)}
              />
              <span>{label}</span>
              {error && <span className="text-[13px] text-danger">{error}</span>}
            </label>
          );
        }

        return (
          <Field key={d.key} label={d.unit ? `${label} (${d.unit})` : label} error={error}>
            {d.dataType === "ENUM" ? (
              <select
                className={inputClass}
                value={typeof value === "string" ? value : ""}
                onChange={(e) => onChange(d.key, e.target.value)}
              >
                <option value="">—</option>
                {(d.enumValues ?? []).map((option) => (
                  <option key={option} value={option}>
                    {option}
                  </option>
                ))}
              </select>
            ) : (
              <input
                className={inputClass + (d.dataType === "NUMBER" ? " tnum text-right" : "")}
                type={d.dataType === "DATE" ? "date" : "text"}
                inputMode={d.dataType === "NUMBER" ? "decimal" : undefined}
                value={typeof value === "string" ? value : ""}
                onChange={(e) => onChange(d.key, e.target.value)}
              />
            )}
          </Field>
        );
      })}
    </div>
  );
}

/**
 * Shown when a product carries fields its category has stopped declaring.
 *
 * The validator rejects unknown keys, so these cannot be sent back — saving
 * anything else on this product drops them. Better said out loud than
 * discovered later by a report that has quietly gone empty.
 */
export function OrphanedFields({ keys }: { keys: string[] }) {
  return (
    <Callout tone="warn">
      This product carries {keys.length === 1 ? "a field" : "fields"} its category no longer
      declares — <span className="font-mono text-[12.5px]">{keys.join(", ")}</span>. Saving here
      drops {keys.length === 1 ? "it" : "them"}.
    </Callout>
  );
}
