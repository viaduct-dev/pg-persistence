export async function request<T>(path: string, body: unknown, token?: string): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error_description || result.error || "Request failed");
  return result;
}

export async function graphql<T>(query: string, variables: Record<string, unknown>, token: string): Promise<T> {
  const result = await request<{ data: T; errors?: { message: string }[] }>("/graphql", { query, variables }, token);
  if (result.errors?.length) throw new Error(result.errors.map(error => error.message).join("; "));
  return result.data;
}

export async function pkceChallenge(verifier: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
export function randomValue(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=/g, "");
}
