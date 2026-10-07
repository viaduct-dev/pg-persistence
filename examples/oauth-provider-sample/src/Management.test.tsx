import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import Management from "./Management";

const page = {
  accounts: [{ id: "account-1", username: "alice", admin: false }],
  groups: [{ id: "group-1", name: "Readers", members: [{ id: "member-1", accountId: "account-1" }] }],
  clients: [{ id: "client-1", name: "Demo", redirectUris: [], scopes: ["demo:read"], enabled: true }],
  accessRules: [{ id: "rule-1", groupId: "group-1", clientId: "client-1", scopes: ["demo:read"] }],
};

function servePage() {
  return vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
    new Response(JSON.stringify({ data: page }), { status: 200 }));
}

describe("Management operations", () => {
  it("registers a client with redirect URLs and scopes from the form", async () => {
    const fetch = servePage();
    render(<Management token="admin-session" />);
    const name = await screen.findByLabelText("Client name");
    fireEvent.change(name, { target: { value: "New client" } });
    fireEvent.change(screen.getByLabelText("Redirect URLs (separated by spaces)"), {
      target: { value: "https://example.com/callback https://other.example.com/callback" },
    });
    fireEvent.change(screen.getByLabelText("Scopes (separated by spaces)"), {
      target: { value: "demo:read demo:write" },
    });
    fireEvent.submit(name.closest("form")!);

    await waitFor(() => expect(fetch.mock.calls).toHaveLength(3));
    expect({
      path: fetch.mock.calls[1][0],
      headers: fetch.mock.calls[1][1]?.headers,
      body: JSON.parse(String(fetch.mock.calls[1][1]?.body)),
    }).toEqual({
      path: "/graphql",
      headers: { "Content-Type": "application/json", Authorization: "Bearer admin-session" },
      body: {
        query: expect.stringContaining("mutation CreateClient("),
        variables: {
          name: "New client",
          redirectUris: ["https://example.com/callback", "https://other.example.com/callback"],
          scopes: ["demo:read", "demo:write"],
        },
      },
    });
  });

  it.each([
    { button: "Remove", operation: "RemoveMember", variables: { id: "member-1" } },
    { button: "Disable", operation: "SetClientEnabled", variables: { id: "client-1", enabled: false } },
    { button: "Delete", operation: "DeleteAccessRule", variables: { id: "rule-1" } },
  ])("sends $operation and reloads the page", async ({ button, operation, variables }) => {
    const fetch = servePage();
    render(<Management token="admin-session" />);
    fireEvent.click(await screen.findByRole("button", { name: button }));

    await waitFor(() => expect(fetch.mock.calls).toHaveLength(3));
    expect(JSON.parse(String(fetch.mock.calls[1][1]?.body))).toEqual({
      query: expect.stringContaining(`mutation ${operation}(`),
      variables,
    });
  });
});
