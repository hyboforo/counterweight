import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiFailure } from "../api/client";
import type { CreatedUser, RoleView, UserSummary } from "../api/types";
import { APPROVAL_PERMISSIONS } from "../auth/approval";
import { useAuth } from "../auth/AuthContext";
import { describe } from "../till/useSale";
import { Button, Callout, Field, Panel, inputClass, useToast } from "../ui/components";

/**
 * Who works here.
 *
 * The screen behind §2's separation of duties, and it is deliberately not a
 * permission editor. What a role carries — that a storekeeper counts but does
 * not post, that sales staff never see cost — is seeded by the migrations and
 * is the shop's control structure written down. Adding COST_VIEW to SALES_STAFF
 * from a screen would undo the reason the role exists, quietly, with no record
 * of who decided it. So roles are read-only here and ROLE_MANAGE stays dormant:
 * changing what a role carries is a migration.
 *
 * What this screen does is staff the shop — create an account, hand out the
 * roles that already exist, and take them back.
 */
export function People() {
  const toast = useToast();
  const { user, can } = useAuth();
  const [people, setPeople] = useState<UserSummary[] | null>(null);
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);

  /**
   * A one-time password on its way to a person.
   *
   * Held here rather than inside whichever panel produced it, because both
   * creating an account and resetting one produce it, and neither can produce
   * it again. It stays on screen until somebody dismisses it.
   */
  const [issued, setIssued] = useState<{ username: string; password: string } | null>(null);

  const load = useCallback(async () => {
    const [all, catalogue] = await Promise.all([
      api.get<UserSummary[]>("/api/admin/users"),
      api.get<RoleView[]>("/api/admin/roles"),
    ]);
    setPeople([...all].sort((a, b) => a.fullName.localeCompare(b.fullName)));
    setRoles(catalogue);
  }, []);

  useEffect(() => {
    void load().catch((err) => toast(describe(err)));
  }, [load, toast]);

  // The open panel addresses a row by id rather than holding a copy of it, so
  // a reload after an action re-renders it with what the server now says.
  const selected = people?.find((p) => p.id === selectedId) ?? null;

  if (!people) return <div className="p-6 text-inksoft">Loading…</div>;

  const active = people.filter((p) => p.isActive).length;

  return (
    <div className="grid grid-rows-[auto_1fr] h-full min-h-0">
      <div className="px-5 py-4 bg-surface border-b border-line flex items-baseline gap-3 flex-wrap">
        <h1 className="m-0 text-xl font-semibold">People</h1>
        <span className="text-[13px] text-inkfaint tnum">
          {active} working here
          {people.length > active && `, ${people.length - active} switched off`}
        </span>
        {can("USER_MANAGE") && (
          <span className="ml-auto">
            <Button variant="quiet" onClick={() => setCreating(true)}>
              Add someone
            </Button>
          </span>
        )}
      </div>

      <div className="overflow-y-auto min-h-0">
        {people.map((p) => (
          <PersonRow
            key={p.id}
            person={p}
            isMe={p.id === user?.id}
            mayApprove={couldBeAskedToApprove(p, roles)}
            onOpen={() => setSelectedId(p.id)}
          />
        ))}
      </div>

      {creating && (
        <NewPerson
          roles={roles}
          onClose={() => setCreating(false)}
          onCreated={(created) => {
            setCreating(false);
            setIssued({ username: created.user.username, password: created.temporaryPassword });
            void load().catch((err) => toast(describe(err)));
          }}
        />
      )}

      {selected && (
        <PersonPanel
          person={selected}
          roles={roles}
          isMe={selected.id === user?.id}
          isLastSeller={isTheLastSeller(selected, people, roles)}
          onClose={() => setSelectedId(null)}
          onChanged={() => void load().catch((err) => toast(describe(err)))}
          onPasswordIssued={(password) => {
            // It replaces the panel rather than stacking on top of it: this is
            // the only time this password will ever be on a screen.
            setSelectedId(null);
            setIssued({ username: selected.username, password });
          }}
        />
      )}

      {issued && (
        <TemporaryPassword
          username={issued.username}
          password={issued.password}
          onClose={() => setIssued(null)}
        />
      )}
    </div>
  );
}

/**
 * Whether this person can ever be asked to approve something at a till.
 *
 * Answered from the role catalogue rather than from a role name, because the
 * catalogue carries what each role actually holds — and it is what decides
 * whether a missing PIN matters or is simply not their job.
 */
function couldBeAskedToApprove(person: UserSummary, roles: RoleView[]): boolean {
  return roles
    .filter((r) => person.roles.includes(r.code))
    .some((r) => r.permissions.some((p) => APPROVAL_PERMISSIONS.includes(p)));
}

function PersonRow({
  person,
  isMe,
  mayApprove,
  onOpen,
}: {
  person: UserSummary;
  isMe: boolean;
  mayApprove: boolean;
  onOpen: () => void;
}) {
  return (
    <button
      onClick={onOpen}
      className={
        "w-full text-left grid grid-cols-[1fr_auto] gap-x-5 items-center px-5 py-3 " +
        "border-b border-linesoft bg-surface hover:bg-surface2 transition-colors " +
        (person.isActive ? "" : "opacity-55")
      }
    >
      <div className="min-w-0">
        <div className="font-medium truncate">
          {person.fullName}
          {isMe && <span className="ml-2 text-[12px] text-inkfaint font-normal">you</span>}
        </div>
        <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-inksoft">
          <span className="font-mono text-xs text-inkfaint">{person.username}</span>
          {person.roles.map((r) => (
            <span key={r}>{roleWords(r)}</span>
          ))}
        </div>
      </div>

      <div className="text-right text-[12.5px]">
        <Status person={person} />
        {mayApprove && !person.hasOverridePin && person.isActive && (
          <div className="text-warn">no till PIN</div>
        )}
        <div className="text-inkfaint">
          {person.lastLoginAt
            ? `last in ${new Date(person.lastLoginAt).toLocaleDateString()}`
            : "never signed in"}
        </div>
      </div>
    </button>
  );
}

/**
 * Whether switching this account off would leave nobody able to sell.
 *
 * Worth asking before the click rather than after. The shop can recover — the
 * system administrator holds USER_MANAGE and can switch the account back on —
 * but a shop that cannot ring anything up, staffed by somebody who has just
 * been told the only role they may grant is "system administrator", is a bad
 * ten minutes that a sentence prevents.
 */
function isTheLastSeller(
  person: UserSummary,
  people: UserSummary[],
  roles: RoleView[],
): boolean {
  const canSell = (p: UserSummary) =>
    roles
      .filter((r) => p.roles.includes(r.code))
      .some((r) => r.permissions.includes("SALE_CREATE"));

  if (!person.isActive || !canSell(person)) return false;
  return people.filter((p) => p.isActive && canSell(p)).length === 1;
}

/**
 * Why most of the roles are greyed out.
 *
 * The rule is one line — you cannot hand out access you do not hold yourself —
 * and until now it was only ever said in small grey text under each refused
 * role, which is not where somebody looks when the list appears mostly
 * disabled. Said once, at the top, before they start clicking.
 *
 * The second sentence is the one that matters on a fresh install. The system
 * administrator holds nothing commercial by design, so signed in as that
 * account the only role on offer is another system administrator — and a shop
 * that has switched its owner off can end up staring at that list wondering
 * where everybody went.
 */
function WhyRolesAreGreyed({ roles }: { roles: RoleView[] }) {
  const grantable = roles.filter((r) => r.grantable);
  if (grantable.length === roles.length) return null;

  const canStaffTheFloor = grantable.some((r) => r.permissions.includes("SALE_CREATE"));

  return (
    <Callout tone={canStaffTheFloor ? "info" : "warn"}>
      You can hand out {grantable.length} of these {roles.length}. You cannot grant
      access you do not hold yourself — that is what stops an account being made
      with more authority than the person making it.
      {!canStaffTheFloor && (
        <>
          {" "}
          <strong>None of the roles you can grant is able to sell.</strong>{" "}
          Staffing the floor is the shop owner's account, not this one. If the
          owner cannot sign in, switch that account back on and reset its
          password from this screen rather than creating another administrator
          here.
        </>
      )}
    </Callout>
  );
}

/** SALES_STAFF reads as "sales staff" to somebody who does not write code. */
const roleWords = (code: string) => code.replace(/_/g, " ").toLowerCase();

function Status({ person }: { person: UserSummary }) {
  if (!person.isActive) return <div className="text-inkfaint font-medium">Switched off</div>;
  if (person.isLocked) return <div className="text-danger font-medium">Locked out</div>;
  if (person.mustChangePassword) {
    return <div className="text-warn font-medium">Has not set a password</div>;
  }
  return <div className="text-good font-medium">Working</div>;
}

/* ── One person ─────────────────────────────────────────────────────────── */

function PersonPanel({
  person,
  roles,
  isMe,
  isLastSeller,
  onClose,
  onChanged,
  onPasswordIssued,
}: {
  person: UserSummary;
  roles: RoleView[];
  isMe: boolean;
  isLastSeller: boolean;
  onClose: () => void;
  onChanged: () => void;
  onPasswordIssued: (password: string) => void;
}) {
  const toast = useToast();
  const { can } = useAuth();
  const [held, setHeld] = useState<string[]>(person.roles);
  const [busy, setBusy] = useState<string | null>(null);

  // Re-syncs when the row behind this panel comes back from the server changed.
  useEffect(() => setHeld(person.roles), [person.roles]);

  const byCode = useMemo(() => new Map(roles.map((r) => [r.code, r])), [roles]);

  /*
   * A role already held that this actor could not hand out.
   *
   * The server checks the whole set it is given, not the difference — so an
   * administrator cannot save this form while the account holds SYSTEM_ADMIN,
   * even leaving every tick exactly as it was. Saying so is better than a form
   * that looks editable and answers 403.
   */
  const untouchable = person.roles.filter((code) => !byCode.get(code)?.grantable);
  const mayAssign = can("ROLE_ASSIGN") && !isMe && untouchable.length === 0;

  const changed =
    held.length !== person.roles.length || held.some((r) => !person.roles.includes(r));

  async function act(what: string, run: () => Promise<unknown>) {
    setBusy(what);
    try {
      await run();
      onChanged();
    } catch (err) {
      toast(describe(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <Panel title={person.fullName} hint={person.username} wide onClose={onClose}>
      <div className="grid gap-1 text-[13.5px]">
        <Line label="Status">
          <Status person={person} />
        </Line>
        {person.phone && <Line label="Phone">{person.phone}</Line>}
        {person.email && <Line label="Email">{person.email}</Line>}
        <Line label="Till PIN">
          {person.hasOverridePin ? (
            <span className="text-good">set</span>
          ) : couldBeAskedToApprove(person, roles) ? (
            <span className="text-warn">
              none — every approval asked of them is refused
            </span>
          ) : (
            <span className="text-inkfaint">not needed for this role</span>
          )}
        </Line>
        <Line label="Added">{new Date(person.createdAt).toLocaleDateString()}</Line>
        <Line label="Last signed in">
          {person.lastLoginAt ? new Date(person.lastLoginAt).toLocaleString() : "never"}
        </Line>
      </div>

      <div className="grid gap-2 pt-1">
        <span className="text-[11px] uppercase tracking-[.06em] text-inkfaint">Roles</span>

        {isMe && (
          <Callout tone="info">
            You cannot change your own roles. Ask another administrator — that is
            what keeps the separation of duties from being decorative.
          </Callout>
        )}
        {!isMe && untouchable.length > 0 && (
          <Callout>
            This account holds {untouchable.map(roleWords).join(", ")}, which carries
            access you do not have yourself. Only somebody who holds it can change
            these roles.
          </Callout>
        )}

        {mayAssign && <WhyRolesAreGreyed roles={roles} />}

        <div className="grid gap-1.5">
          {roles.map((role) => {
            const ticked = held.includes(role.code);
            const locked = !mayAssign || (!role.grantable && !ticked);
            return (
              <label
                key={role.code}
                className={
                  "grid grid-cols-[auto_1fr] gap-2.5 items-start p-2 rounded " +
                  (locked ? "opacity-60" : "cursor-pointer hover:bg-surface2")
                }
              >
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={ticked}
                  disabled={locked}
                  onChange={(e) =>
                    setHeld((current) =>
                      e.target.checked
                        ? [...current, role.code]
                        : current.filter((c) => c !== role.code),
                    )
                  }
                />
                <span>
                  <span className="font-medium text-[13.5px]">{role.name}</span>
                  <span className="block text-[12px] text-inkfaint leading-relaxed">
                    {role.permissions.length} permissions
                    {role.withheld.length > 0 && (
                      <> — {role.withheld.length} of them you do not hold yourself</>
                    )}
                  </span>
                </span>
              </label>
            );
          })}
        </div>

        {mayAssign && (
          <div className="flex gap-2 items-center flex-wrap">
            <Button
              disabled={!changed || held.length === 0 || busy !== null}
              onClick={() =>
                void act("roles", () =>
                  api.put(`/api/admin/users/${person.id}/roles`, { roles: held }),
                )
              }
            >
              {busy === "roles" ? "Saving…" : "Save roles"}
            </Button>
            {held.length === 0 ? (
              <span className="text-[12.5px] text-danger">A person needs at least one role.</span>
            ) : (
              changed && (
                <span className="text-[12.5px] text-inksoft max-w-[38ch]">
                  Saving signs them out everywhere — what they may do is inside the
                  token they are holding.
                </span>
              )
            )}
          </div>
        )}
      </div>

      {person.isActive && isLastSeller && can("USER_MANAGE") && (
        <Callout tone="danger">
          This is the only account left that can sell. Switching it off stops the
          shop trading, and the system administrator cannot make a replacement —
          it holds nothing commercial by design, so it can only ever create
          another system administrator. Give somebody else a selling role first.
        </Callout>
      )}

      <div className="flex gap-2 flex-wrap items-center pt-3 border-t border-linesoft">
        {can("PASSWORD_RESET") && (
          <Button
            variant="quiet"
            disabled={busy !== null}
            onClick={() =>
              void act("reset", async () => {
                const body = await api.post<{ temporaryPassword: string }>(
                  `/api/admin/users/${person.id}/reset-password`,
                );
                onPasswordIssued(body.temporaryPassword);
              })
            }
          >
            {busy === "reset" ? "Resetting…" : "Reset their password"}
          </Button>
        )}

        {can("PASSWORD_RESET") && person.hasOverridePin && (
          <Button
            variant="quiet"
            disabled={busy !== null}
            onClick={() =>
              void act("pin", () => api.del(`/api/admin/users/${person.id}/override-pin`))
            }
          >
            {busy === "pin" ? "Clearing…" : "Clear their till PIN"}
          </Button>
        )}

        {can("USER_MANAGE") && !isMe && (
          <Button
            variant="quiet"
            disabled={busy !== null}
            className={person.isActive ? "!text-danger !border-danger/40" : ""}
            onClick={() =>
              void act("active", () =>
                api.put(`/api/admin/users/${person.id}/active`, { active: !person.isActive }),
              )
            }
          >
            {busy === "active"
              ? "Working…"
              : person.isActive
                ? "Switch this account off"
                : "Switch it back on"}
          </Button>
        )}
      </div>

      {can("USER_MANAGE") && !isMe && (
        <p className="m-0 text-[12.5px] text-inksoft leading-relaxed">
          Accounts are switched off, never deleted — every sale, count and
          adjustment they made points back at this row. A till PIN can be taken
          away here and set only by its owner, from their own account: an
          override is recorded as theirs, and a PIN you chose would put their
          name on an approval they never gave.
        </p>
      )}
    </Panel>
  );
}

function Line({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[140px_1fr] gap-3">
      <span className="text-inkfaint">{label}</span>
      <div>{children}</div>
    </div>
  );
}

/* ── Adding someone ─────────────────────────────────────────────────────── */

function NewPerson({
  roles,
  onClose,
  onCreated,
}: {
  roles: RoleView[];
  onClose: () => void;
  onCreated: (created: CreatedUser) => void;
}) {
  const toast = useToast();
  const { user } = useAuth();
  const [username, setUsername] = useState("");
  const [fullName, setFullName] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [held, setHeld] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  async function create() {
    setBusy(true);
    setErrors({});
    try {
      onCreated(
        await api.post<CreatedUser>("/api/admin/users", {
          username: username.trim().toLowerCase(),
          fullName: fullName.trim(),
          roles: held,
          // One shop, one branch. §5.1 keeps branch two dormant until there is
          // one, so there is nothing here to choose between.
          branchId: user?.branchId ?? 1,
          phone: phone.trim() === "" ? null : phone.trim(),
          email: email.trim() === "" ? null : email.trim(),
        }),
      );
    } catch (err) {
      if (err instanceof ApiFailure) setErrors(err.fieldErrors);
      toast(describe(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Panel title="Add someone" wide onClose={onClose}>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Their name" error={errors.fullName}>
          <input
            className={inputClass}
            value={fullName}
            autoFocus
            placeholder="Ama Mensah"
            onChange={(e) => setFullName(e.target.value)}
          />
        </Field>
        <Field label="Username — what they type to sign in" error={errors.username}>
          <input
            className={inputClass + " font-mono"}
            value={username}
            placeholder="ama"
            spellCheck={false}
            onChange={(e) => setUsername(e.target.value)}
          />
        </Field>
        <Field label="Phone" error={errors.phone}>
          <input
            className={inputClass + " tnum"}
            value={phone}
            placeholder="024 000 0000"
            onChange={(e) => setPhone(e.target.value)}
          />
        </Field>
        <Field label="Email" error={errors.email}>
          <input
            className={inputClass}
            value={email}
            type="email"
            onChange={(e) => setEmail(e.target.value)}
          />
        </Field>
      </div>

      <div className="grid gap-1.5">
        <span className="text-[11px] uppercase tracking-[.06em] text-inkfaint">
          What they do here
        </span>
        {errors.roles && <span className="text-[13px] text-danger">{errors.roles}</span>}
        <WhyRolesAreGreyed roles={roles} />
        {roles.map((role) => (
          <label
            key={role.code}
            className={
              "grid grid-cols-[auto_1fr] gap-2.5 items-start p-2 rounded " +
              (role.grantable ? "cursor-pointer hover:bg-surface2" : "opacity-60")
            }
          >
            <input
              type="checkbox"
              className="mt-1"
              checked={held.includes(role.code)}
              disabled={!role.grantable}
              onChange={(e) =>
                setHeld((current) =>
                  e.target.checked
                    ? [...current, role.code]
                    : current.filter((c) => c !== role.code),
                )
              }
            />
            <span>
              <span className="font-medium text-[13.5px]">{role.name}</span>
              <span className="block text-[12px] text-inkfaint leading-relaxed">
                {role.grantable
                  ? `${role.permissions.length} permissions`
                  : `You cannot hand this one out — it carries ${role.withheld
                      .map(roleWords)
                      .join(", ")}, which you do not hold yourself.`}
              </span>
            </span>
          </label>
        ))}
      </div>

      <div className="flex gap-2">
        <Button
          variant="primary"
          disabled={busy || fullName.trim() === "" || username.trim() === "" || held.length === 0}
          onClick={() => void create()}
        >
          {busy ? "Creating…" : "Create the account"}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Cancel
        </Button>
      </div>
    </Panel>
  );
}

/* ── The one-time password ──────────────────────────────────────────────── */

/**
 * Shown once, and the server cannot produce it again.
 *
 * Deliberately not emailed or texted: this shop has no gateway wired, and a
 * password sitting in a WhatsApp thread outlives the ten minutes it is meant to
 * exist for. It is read out, typed in, and replaced on first sign-in.
 */
function TemporaryPassword({
  username,
  password,
  onClose,
}: {
  username: string;
  password: string;
  onClose: () => void;
}) {
  const toast = useToast();

  return (
    <Panel title={`Password for ${username}`} onClose={onClose}>
      <Callout>
        This is shown once. It cannot be looked up again — if it is lost, reset
        the password and hand out a new one.
      </Callout>

      <div className="bg-surface2 border border-line rounded px-4 py-4 text-center">
        <div className="font-mono text-xl font-semibold tracking-[.02em] select-all break-all">
          {password}
        </div>
      </div>

      <p className="m-0 text-[13px] text-inksoft leading-relaxed">
        They sign in with this and are asked to replace it before anything else
        works. Until they do, the account can do nothing at all.
      </p>

      <div className="flex gap-2">
        <Button
          onClick={() => {
            /*
             * `navigator.clipboard` is undefined outside a secure context, and
             * a till reaches this server over plain http on the shop LAN. So
             * the button says what happened either way rather than appearing
             * to do nothing.
             */
            const copy = navigator.clipboard?.writeText(password);
            if (!copy) {
              toast({ title: "Select it and copy it by hand", tone: "warn" });
              return;
            }
            void copy
              .then(() => toast({ title: "Copied", tone: "info" }))
              .catch(() =>
                toast({ title: "Could not copy it — read it out instead", tone: "warn" }),
              );
          }}
        >
          Copy it
        </Button>
        <Button variant="quiet" onClick={onClose}>
          Done — I have it
        </Button>
      </div>
    </Panel>
  );
}
