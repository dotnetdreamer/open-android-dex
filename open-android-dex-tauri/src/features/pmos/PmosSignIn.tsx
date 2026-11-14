import { useState } from "react";
import { Icon } from "../../ui/Icon";

/**
 * Signing in to a postmarketOS phone, once: the username and password its
 * owner uses on it. pmos.rs uses them to leave this computer's key on the
 * phone and keeps neither, so the next plug-in asks for nothing.
 */
export function PmosSignIn({
  busy,
  error,
  onSignIn,
  bare = false,
}: {
  busy: boolean;
  error: string;
  onSignIn: (user: string, password: string) => void;
  /** Inside a card of its own: no card around the form. */
  bare?: boolean;
}) {
  // "user" is what postmarketOS's installer suggests, and what most phones have.
  const [user, setUser] = useState("user");
  const [password, setPassword] = useState("");
  const ready = user.trim().length > 0 && password.length > 0 && !busy;
  const submit = () => {
    if (ready) onSignIn(user.trim(), password);
  };
  return (
    <form
      className={bare ? "flex flex-col gap-3" : "card flex flex-col gap-3 px-4 py-4"}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <label className="flex flex-col gap-1.5">
        <span className="section-label">Username on the phone</span>
        <input
          value={user}
          onChange={(e) => setUser(e.target.value)}
          autoComplete="off"
          spellCheck={false}
          aria-label="Username on the phone"
          className="field"
        />
      </label>
      <label className="flex flex-col gap-1.5">
        <span className="section-label">Password</span>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="off"
          autoFocus
          aria-label="Password on the phone"
          placeholder="The one you unlock the phone with"
          className="field"
        />
      </label>
      {error && (
        <div className="callout callout-bad">
          <Icon name="alert" size={16} />
          <div className="min-w-0 flex-1 select-text">{error}</div>
        </div>
      )}
      <button type="submit" className="btn btn-primary btn-lg self-center" disabled={!ready}>
        {busy ? <span className="spinner" aria-hidden="true" /> : <Icon name="play" size={15} />}
        {busy ? "Signing in…" : "Sign in and open desktop"}
      </button>
      <p className="caption text-center">
        Your password goes to the phone once and isn't kept. This computer leaves a key on the phone instead, so next
        time the desktop just opens.
      </p>
    </form>
  );
}
