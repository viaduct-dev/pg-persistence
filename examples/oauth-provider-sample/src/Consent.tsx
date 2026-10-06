import { useEffect, useState } from "react";
import { graphql, request } from "./api";

export default function Consent({ token }: { token: string }) {
  const [allowed, setAllowed] = useState<boolean>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [parameters] = useState(() => new URLSearchParams(location.search));
  const clientId = parameters.get("client_id") || "";
  const scopes = (parameters.get("scope") || "").split(" ").filter(Boolean);
  const scopesKey = scopes.join(" ");
  useEffect(() => {
    graphql<{ accessDecision: { allowed: boolean } }>(
      "query($clientId:ID!,$scopes:[String!]!){accessDecision(clientId:$clientId,scopes:$scopes){allowed}}",
      { clientId, scopes: scopesKey.split(" ") }, token,
    ).then(data => setAllowed(data.accessDecision.allowed)).catch(error => setError(error.message));
  }, [clientId, scopesKey, token]);

  async function decide(approved: boolean) {
    setBusy(true);
    try {
      const result = await request<{ redirectUri: string }>("/oauth/authorize", {
        clientId, redirectUri: parameters.get("redirect_uri"), scopes,
        codeChallenge: parameters.get("code_challenge"), state: parameters.get("state"), approved,
      }, token);
      location.assign(result.redirectUri);
    } catch (error) { setError((error as Error).message); setBusy(false); }
  }
  return <section><h2>Authorize access</h2><p>The client requests: <strong>{scopes.join(", ")}</strong></p>
    <p>Redirect: {parameters.get("redirect_uri")}</p>
    {allowed === false && <p>Your groups do not grant these scopes.</p>}
    {error && <p role="alert" className="error">{error}</p>}
    <button disabled={busy || allowed !== true} onClick={() => decide(true)}>Allow</button>
    <button disabled={busy} onClick={() => decide(false)}>Deny</button>
  </section>;
}
