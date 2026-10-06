import { useState, type FormEvent, type ReactNode } from "react";
import { request } from "./api";
import Management from "./Management";
import Consent from "./Consent";
import DemoClient from "./DemoClient";

export default function App() {
  const [session, setSession] = useState<{ accessToken: string; admin: boolean }>();
  const [error, setError] = useState("");
  const path = location.pathname;

  if (path === "/demo-client") return <main><DemoClient /></main>;

  async function login(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    const fields = new FormData(event.currentTarget);
    try {
      setSession(await request("/session", { username: fields.get("username"), password: fields.get("password") }));
    } catch (error) { setError((error as Error).message); }
  }

  let content: ReactNode;
  if (!session) {
    content = <section><h2>Sign in</h2><form onSubmit={login}>
      <label>Username<input name="username" autoComplete="username" required /></label>
      <label>Password<input name="password" type="password" autoComplete="current-password" required /></label>
      <button>Sign in</button>
    </form><p>Use the administrator account to configure users, groups, clients, and access rules.</p></section>;
  } else if (path === "/authorize") {
    content = <Consent token={session.accessToken} />;
  } else if (session.admin) {
    content = <Management token={session.accessToken} />;
  } else {
    content = <section><h2>Signed in</h2><p>Open the demo client to request access through OAuth.</p><a href="/demo-client">Demo client</a></section>;
  }
  return <main><header><div><h1>OAuth provider sample</h1><p>Users → groups → access rules → OAuth scopes</p></div>
    <nav><a href="/">Manage</a><a href="/demo-client">Demo client</a>{session && <button onClick={() => setSession(undefined)}>Sign out</button>}</nav></header>
    {error && <p role="alert" className="error">{error}</p>}{content}</main>;
}
