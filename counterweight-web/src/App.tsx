import { useEffect, useState } from "react";
import { AuthProvider, LoginScreen, useAuth } from "./auth/AuthContext";
import { ChangePasswordScreen } from "./auth/ChangePassword";
import { MyAccount } from "./auth/MyAccount";
import { BackOffice } from "./backoffice/BackOffice";
import { TillScreen } from "./till/TillScreen";
import { tillCode } from "./till/tillCode";
import { ToastHost } from "./ui/components";

function Shell() {
  const { user, loading, can, mustChangePassword, reload } = useAuth();
  const [inBackOffice, setInBackOffice] = useState(false);
  const [changingPassword, setChangingPassword] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);

  /*
   * The room resets with the person.
   *
   * One till, several people, and none of this state unmounts when somebody
   * signs out. Without this the cashier coming on after a supervisor who was
   * reading reports lands in the back office — a screen she can technically
   * open, and not the one she came to work at.
   */
  useEffect(() => {
    setInBackOffice(false);
    setChangingPassword(false);
    setAccountOpen(false);
  }, [user?.id]);

  /*
   * Which rooms this person can be in.
   *
   * A storekeeper holds STOCK_COUNT and STOCK_RECEIVE and not SALE_CREATE, so
   * sending everyone to the till would strand them on a screen they are not
   * permitted to use. Whoever cannot sell lands in the back office.
   *
   * REPORT_VIEW and AUDIT_VIEW belong in that list for the same reason. An
   * auditor or a bookkeeper holds neither stock nor customer permissions and
   * has nothing to do at a till; without them here the back office would be a
   * room they can see the reports of and never walk into.
   */
  const maySell = can("SALE_CREATE");
  const mayBackOffice =
    can("STOCK_COUNT") ||
    can("STOCK_RECEIVE") ||
    can("CUSTOMER_MANAGE") ||
    can("PRODUCT_MANAGE") ||
    can("REPORT_VIEW") ||
    can("AUDIT_VIEW") ||
    // The system administrator: no stock, no customers, nothing commercial at
    // all by design. Without these the one account that staffs the shop and
    // holds its settings would be told it has nowhere to be.
    can("USER_MANAGE") ||
    can("ROLE_ASSIGN") ||
    can("CONFIG_MANAGE") ||
    can("BACKUP_MANAGE");

  if (loading) {
    return <div className="h-full grid place-items-center text-inksoft">Loading…</div>;
  }
  if (!user) return <LoginScreen />;

  /*
   * One thing stands in front of the till, and only one.
   *
   * A temporary password was typed on paper by somebody else and read by
   * whoever walked past. The server refuses every request from this account
   * until it is replaced, so rendering anything else here would be a screen
   * that answers 403 to its own first request.
   */
  if (mustChangePassword) {
    return <ChangePasswordScreen username={user.username} onChanged={() => void reload()} />;
  }

  /*
   * The same screen, asked for rather than imposed.
   *
   * Somebody who thinks their password was watched over their shoulder needs
   * to be able to change it without finding an administrator, and the server
   * has always allowed it. It takes over the screen rather than opening in a
   * panel because succeeding signs every other session out, this browser's
   * included, and there is nothing behind it to go back to until it does.
   */
  if (changingPassword) {
    return (
      <ChangePasswordScreen
        username={user.username}
        onChanged={() => {
          setChangingPassword(false);
          void reload();
        }}
        onCancel={() => setChangingPassword(false)}
      />
    );
  }

  /*
   * Signing in lands on the selling screen. Nothing stands in front of it.
   *
   * There is no drawer in this shop — no float to count, no session to open,
   * nothing to reconcile at the end of the day. The takings are the payments,
   * and the payments are recorded by the sale itself.
   */
  const room =
    !maySell || (inBackOffice && mayBackOffice) ? (
      mayBackOffice ? (
        <BackOffice
          onGoToTill={maySell ? () => setInBackOffice(false) : null}
          onOpenAccount={() => setAccountOpen(true)}
        />
      ) : (
        <div className="h-full grid place-items-center p-6 text-center">
          <p className="m-0 max-w-[42ch] text-inksoft">
            This account can neither sell, handle stock, nor read reports. Ask whoever set it up
            which permissions it is supposed to hold.
          </p>
        </div>
      )
    ) : (
      <TillScreen
        tillCode={tillCode()}
        onGoToBackOffice={mayBackOffice ? () => setInBackOffice(true) : undefined}
        onOpenAccount={() => setAccountOpen(true)}
      />
    );

  return (
    <>
      {room}
      {/*
        * Over whichever room they are in rather than instead of it: setting a
        * till PIN is a thirty-second errand between customers, and losing the
        * basket behind it would make it one nobody runs.
        */}
      {accountOpen && (
        <MyAccount
          onClose={() => setAccountOpen(false)}
          onChangePassword={() => {
            setAccountOpen(false);
            setChangingPassword(true);
          }}
        />
      )}
    </>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <ToastHost>
        <Shell />
      </ToastHost>
    </AuthProvider>
  );
}
