import { useState } from "react";
import type { StockTakeView } from "../api/types";
import { useAuth } from "../auth/AuthContext";
import { People } from "../admin/People";
import { Settings } from "../admin/Settings";
import { CountSheet } from "../stock/CountSheet";
import { Catalogue } from "../catalog/Catalogue";
import { Receivables } from "../parties/Receivables";
import { GoodsReceipt } from "../stock/GoodsReceipt";
import { Reports } from "../reports/Reports";
import { StockTakeList } from "../stock/StockTakeList";
import { VarianceReview } from "../stock/VarianceReview";
import { Button } from "../ui/components";

type Section =
  | "receipts"
  | "takes"
  | "catalogue"
  | "receivables"
  | "reports"
  | "people"
  | "settings";

type Screen =
  | { name: "list" }
  | { name: "sheet"; take: StockTakeView }
  | { name: "review"; take: StockTakeView };

/**
 * The back office.
 *
 * A different room from the till: no scanner racing the caret
 * to a basket, and time to read. Sections are permission-gated rather than
 * hidden behind a role name, so an account that can receive but not count sees
 * one tab and no dead ends.
 */
export function BackOffice({
  onGoToTill,
  onOpenAccount,
}: {
  onGoToTill: (() => void) | null;
  onOpenAccount: () => void;
}) {
  const { user, signOut, can } = useAuth();
  const mayReceive = can("STOCK_RECEIVE");
  const mayCount = can("STOCK_COUNT");
  const mayCollect = can("CUSTOMER_MANAGE");
  // The catalogue is PRODUCT_MANAGE work throughout — creating, renaming,
  // units. PRICE_VIEW and PRICE_MANAGE only decide how much of a product
  // screen a storekeeper sees once they are in it.
  const mayCatalogue = can("PRODUCT_MANAGE");
  // Two different permissions open this tab: REPORT_VIEW for the trading
  // reports, AUDIT_VIEW for the control registers. An auditor holds the second
  // and none of the others, and has no reason to see a goods receipt form.
  const mayReport = can("REPORT_VIEW") || can("AUDIT_VIEW");
  // Two permissions again, and for the same reason: ROLE_ASSIGN without
  // USER_MANAGE is a real account — somebody who may move an existing person
  // between roles but not open an account for a new one.
  const mayStaff = can("USER_MANAGE") || can("ROLE_ASSIGN");
  // CONFIG_MANAGE and BACKUP_MANAGE are the system administrator's, not the
  // owner's. The shop's settings and the state of its backups are the two
  // things on this screen that are not about trading at all.
  const maySettings = can("CONFIG_MANAGE") || can("BACKUP_MANAGE");

  const [section, setSection] = useState<Section>(
    mayReceive
      ? "receipts"
      : mayCount
        ? "takes"
        : mayCatalogue
          ? "catalogue"
          : mayCollect
            ? "receivables"
            : mayReport
              ? "reports"
              : mayStaff
                ? "people"
                : "settings",
  );
  const [screen, setScreen] = useState<Screen>({ name: "list" });
  const [nonce, setNonce] = useState(0);

  /** Re-reads a header after the sheet changed underneath it. */
  const refresh = () => setNonce((n) => n + 1);

  const go = (next: Section) => {
    setSection(next);
    setScreen({ name: "list" });
  };

  return (
    <div className="h-full grid grid-rows-[auto_auto_1fr] bg-ground min-h-0">
      <div className="flex items-center gap-5 flex-wrap px-4 h-10 bg-surface border-b border-line text-[12.5px] text-inksoft">
        <span className="font-mono font-semibold tracking-[.04em] text-ink">BACK OFFICE</span>
        <span>
          <strong className="text-ink font-semibold">{user?.username}</strong>
        </span>
        <span className="ml-auto flex gap-2">
          {onGoToTill && <Button variant="quiet" onClick={onGoToTill}>Go to the till</Button>}
          <Button variant="quiet" onClick={onOpenAccount}>My account</Button>
          <Button variant="quiet" onClick={() => void signOut()}>Sign out</Button>
        </span>
      </div>

      <nav className="flex gap-1 px-4 bg-surface border-b border-line" aria-label="Back office sections">
        {mayReceive && (
          <Tab active={section === "receipts"} onClick={() => go("receipts")}>
            Goods received
          </Tab>
        )}
        {mayCount && (
          <Tab active={section === "takes"} onClick={() => go("takes")}>
            Stock takes
          </Tab>
        )}
        {mayCatalogue && (
          <Tab active={section === "catalogue"} onClick={() => go("catalogue")}>
            Catalogue
          </Tab>
        )}
        {mayCollect && (
          <Tab active={section === "receivables"} onClick={() => go("receivables")}>
            Money owed
          </Tab>
        )}
        {mayReport && (
          <Tab active={section === "reports"} onClick={() => go("reports")}>
            Reports
          </Tab>
        )}
        {mayStaff && (
          <Tab active={section === "people"} onClick={() => go("people")}>
            People
          </Tab>
        )}
        {maySettings && (
          <Tab active={section === "settings"} onClick={() => go("settings")}>
            Settings
          </Tab>
        )}
      </nav>

      <div className="min-h-0">
        {section === "receipts" && mayReceive && <GoodsReceipt />}

        {section === "catalogue" && mayCatalogue && <Catalogue />}

        {section === "receivables" && mayCollect && <Receivables />}

        {section === "reports" && mayReport && <Reports />}

        {section === "people" && mayStaff && <People />}

        {section === "settings" && maySettings && <Settings />}

        {section === "takes" && mayCount && (
          <>
            {screen.name === "list" && (
              <StockTakeList
                key={nonce}
                onOpenSheet={(take) => setScreen({ name: "sheet", take })}
                onReview={(take) => setScreen({ name: "review", take })}
              />
            )}

            {screen.name === "sheet" && (
              <CountSheet
                take={screen.take}
                onChanged={refresh}
                onSignedOff={() => {
                  refresh();
                  setScreen({ name: "list" });
                }}
                onBack={() => setScreen({ name: "list" })}
              />
            )}

            {screen.name === "review" && (
              <VarianceReview
                take={screen.take}
                onPosted={refresh}
                onBack={() => setScreen({ name: "list" })}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}

function Tab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={
        "px-3.5 py-2.5 text-[13.5px] font-medium border-b-2 -mb-px transition-colors " +
        (active
          ? "border-accent text-accent"
          : "border-transparent text-inksoft hover:text-ink")
      }
    >
      {children}
    </button>
  );
}
