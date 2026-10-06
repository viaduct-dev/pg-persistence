import { useCallback, useEffect, useState, type FormEvent } from "react";
import { graphql } from "./api";

type Account = { id: string; username: string; admin: boolean };
type Group = { id: string; name: string; members: { id: string; accountId: string }[] };
type Client = { id: string; name: string; redirectUris: string[]; scopes: string[]; enabled: boolean };
type Rule = { id: string; groupId: string; clientId: string; scopes: string[] };
type Data = { accounts: Account[]; groups: Group[]; clients: Client[]; accessRules: Rule[] };

const PAGE = `
  fragment AccountSummary on Account { id username admin }
  fragment GroupSummary on Group { id name members { id accountId } }
  fragment ClientSummary on OAuthClient { id name redirectUris scopes enabled }
  query ManagementPage {
    accounts { ...AccountSummary }
    groups { ...GroupSummary }
    clients { ...ClientSummary }
    accessRules { id groupId clientId scopes }
  }
`;

export default function Management({ token }: { token: string }) {
  const [data, setData] = useState<Data>();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => setData(await graphql<Data>(PAGE, {}, token)), [token]);
  useEffect(() => { load().catch(error => setError(error.message)); }, [load]);

  async function mutate(query: string, variables: Record<string, unknown>) {
    setBusy(true); setError("");
    try { await graphql(query, variables, token); await load(); return true; }
    catch (error) { setError((error as Error).message); return false; }
    finally { setBusy(false); }
  }
  function form(query: string, fields: (data: FormData) => Record<string, unknown>) {
    return async (event: FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const element = event.currentTarget;
      if (await mutate(query, fields(new FormData(element)))) element.reset();
    };
  }
  const split = (data: FormData, name: string) => String(data.get(name) || "").trim().split(/\s+/).filter(Boolean);
  const select = (name: string, entries: { id: string; name: string }[]) => <select name={name} required>
    <option value="">Choose…</option>{entries.map(entry => <option key={entry.id} value={entry.id}>{entry.name}</option>)}</select>;
  if (!data) return <p>{error || "Loading…"}</p>;

  return <><p>All page data comes from one GraphQL operation. Accounts and grants are stored in the schema-generated database.</p>
    {error && <p role="alert" className="error">{error}</p>}<div className="grid">
    <section><h2>Users</h2><ul>{data.accounts.map(account => <li key={account.id}>{account.username}{account.admin ? " (administrator)" : ""}</li>)}</ul>
      <form onSubmit={form("mutation($username:String!,$password:String!){createAccount(username:$username,password:$password){id}}",
        fields => ({ username: fields.get("username"), password: fields.get("password") }))}>
        <label>Username<input name="username" pattern={"[a-z][a-z0-9_\\-]{2,31}"} required /></label>
        <label>Password<input name="password" type="password" minLength={12} maxLength={256} required /></label>
        <button disabled={busy}>Create user</button>
      </form></section>
    <section><h2>Groups</h2>{data.groups.map(group => <div key={group.id}><h3>{group.name}</h3>
      <ul>{group.members.map(member => <li key={member.id}>
        {data.accounts.find(account => account.id === member.accountId)?.username || member.accountId}
        <button disabled={busy} onClick={() => mutate("mutation($id:ID!){removeMember(id:$id)}", { id: member.id })}>Remove</button>
      </li>)}</ul></div>)}
      <form onSubmit={form("mutation($name:String!){createGroup(name:$name){id}}", fields => ({ name: fields.get("name") }))}>
        <label>Group name<input name="name" required maxLength={100} /></label><button disabled={busy}>Create group</button>
      </form>
      <form onSubmit={form("mutation($accountId:ID!,$groupId:ID!){addMember(accountId:$accountId,groupId:$groupId){id}}",
        fields => ({ accountId: fields.get("accountId"), groupId: fields.get("groupId") }))}>
        <label>User{select("accountId", data.accounts.map(account => ({ id: account.id, name: account.username })))}</label>
        <label>Group{select("groupId", data.groups)}</label><button disabled={busy}>Add member</button>
      </form></section>
    <section><h2>OAuth clients</h2>{data.clients.map(client => <article key={client.id}><h3>{client.name}</h3>
      <label>Client ID<input readOnly value={client.id} /></label>
      <p>{client.scopes.join(", ")} · {client.enabled ? "enabled" : "disabled"}</p>
      <button disabled={busy} onClick={() => mutate("mutation($id:ID!,$enabled:Boolean!){setClientEnabled(id:$id,enabled:$enabled)}",
        { id: client.id, enabled: !client.enabled })}>{client.enabled ? "Disable" : "Enable"}</button>
    </article>)}
      <form onSubmit={form("mutation($name:String!,$redirectUris:[String!]!,$scopes:[String!]!){createClient(name:$name,redirectUris:$redirectUris,scopes:$scopes){id}}",
        fields => ({ name: fields.get("name"), redirectUris: split(fields, "redirectUris"), scopes: split(fields, "scopes") }))}>
        <label>Client name<input name="name" required /></label>
        <label>Redirect URLs (separated by spaces)<input name="redirectUris" defaultValue={location.origin + "/demo-client"} required /></label>
        <label>Scopes (separated by spaces)<input name="scopes" defaultValue="demo:read" required /></label>
        <button disabled={busy}>Register client</button>
      </form></section>
    <section><h2>Access rules</h2><ul>{data.accessRules.map(rule => <li key={rule.id}>
      {data.groups.find(group => group.id === rule.groupId)?.name} → {data.clients.find(client => client.id === rule.clientId)?.name}: {rule.scopes.join(", ")}
      <button disabled={busy} onClick={() => mutate("mutation($id:ID!){deleteAccessRule(id:$id)}", { id: rule.id })}>Delete</button>
    </li>)}</ul><form onSubmit={form("mutation($groupId:ID!,$clientId:ID!,$scopes:[String!]!){createAccessRule(groupId:$groupId,clientId:$clientId,scopes:$scopes){id}}",
      fields => ({ groupId: fields.get("groupId"), clientId: fields.get("clientId"), scopes: split(fields, "scopes") }))}>
      <label>Group{select("groupId", data.groups)}</label><label>Client{select("clientId", data.clients)}</label>
      <label>Allowed scopes<input name="scopes" defaultValue="demo:read" required /></label><button disabled={busy}>Create access rule</button>
    </form></section>
  </div></>;
}
