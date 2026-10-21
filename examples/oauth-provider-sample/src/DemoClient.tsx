import { useEffect, useRef, useState, type FormEvent } from "react";
import { pkceChallenge, randomValue } from "./api";

export default function DemoClient() {
  const started = useRef(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<{ username: string; scope: string }>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    const parameters = new URLSearchParams(location.search);
    if (!parameters.has("code") && !parameters.has("error")) return;
    const saved = sessionStorage.getItem("oauth-demo-pending");
    sessionStorage.removeItem("oauth-demo-pending");
    history.replaceState(null, "", "/demo-client");
    if (!saved) { setError("No pending authorization request"); return; }
    const pending = JSON.parse(saved);
    if (parameters.get("state") !== pending.state) { setError("Authorization state did not match"); return; }
    if (parameters.has("error")) { setError(parameters.get("error")!); return; }
    setBusy(true);
    const body = new URLSearchParams({
      grant_type: "authorization_code", code: parameters.get("code")!,
      client_id: pending.clientId, redirect_uri: pending.redirectUri, code_verifier: pending.verifier,
    });
    fetch("/oauth/token", { method: "POST", body }).then(async response => {
      const tokens = await response.json();
      if (!response.ok) throw new Error(tokens.error_description || tokens.error);
      const resource = await fetch("/demo-resource", { headers: { Authorization: `Bearer ${tokens.access_token}` } });
      const value = await resource.json();
      if (!resource.ok) throw new Error(value.error);
      setResult(value);
    }).catch(error => setError(error.message)).finally(() => setBusy(false));
  }, []);

  async function begin(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setBusy(true);
    try {
      const clientId = String(new FormData(event.currentTarget).get("clientId"));
      const verifier = randomValue(), state = randomValue(), redirectUri = location.origin + "/demo-client";
      sessionStorage.setItem("oauth-demo-pending", JSON.stringify({ clientId, verifier, state, redirectUri }));
      const query = new URLSearchParams({
        response_type: "code", client_id: clientId, redirect_uri: redirectUri, scope: "demo:read",
        code_challenge: await pkceChallenge(verifier), code_challenge_method: "S256", state,
      });
      location.assign("/oauth/authorize?" + query);
    } catch (error) { setError((error as Error).message); setBusy(false); }
  }

  return <section><h1>Demo OAuth client</h1><p>Register a client with redirect URL <code>{location.origin}/demo-client</code> and scope <code>demo:read</code>.</p>
    <form onSubmit={begin}><label>Client ID<input name="clientId" required /></label><button disabled={busy}>Request access</button></form>
    {busy && <p>Working…</p>}{error && <p role="alert" className="error">{error}</p>}
    {result && <p role="status">OAuth succeeded. The protected resource identifies <strong>{result.username}</strong> with scope <strong>{result.scope}</strong>.</p>}
    <a href="/">Manage users, groups, and clients</a>
  </section>;
}
