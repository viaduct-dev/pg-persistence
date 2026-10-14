import { StrictMode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import DemoClient from "./DemoClient";
import { pkceChallenge } from "./api";

describe("OAuth demo client", () => {
  it("uses the RFC 7636 S256 example", async () => {
    expect(await pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });

  it("rejects a callback with mismatched state before making requests", async () => {
    history.replaceState(null, "", "/demo-client?code=some-code&state=wrong");
    sessionStorage.setItem("oauth-demo-pending", JSON.stringify({ state: "expected", verifier: "verifier", clientId: "id" }));
    const fetch = vi.spyOn(globalThis, "fetch");
    render(<StrictMode><DemoClient /></StrictMode>);
    expect((await screen.findByRole("alert")).textContent === "Authorization state did not match" && fetch.mock.calls.length === 0).toBe(true);
  });

  it("redeems once under React StrictMode and uses the access token at the resource", async () => {
    history.replaceState(null, "", "/demo-client?code=some-code&state=expected");
    sessionStorage.setItem("oauth-demo-pending", JSON.stringify({ state: "expected", verifier: "verifier", clientId: "id", redirectUri: "http://localhost/demo-client" }));
    const fetch = vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(new Response(JSON.stringify({ access_token: "test-access-token" }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ username: "alice", scope: "demo:read" }), { status: 200 }));
    render(<StrictMode><DemoClient /></StrictMode>);
    await waitFor(() => screen.getByRole("status"));
    expect(fetch.mock.calls.length === 2 && fetch.mock.calls[0][0] === "/oauth/token" && fetch.mock.calls[1][0] === "/demo-resource").toBe(true);
  });
});
