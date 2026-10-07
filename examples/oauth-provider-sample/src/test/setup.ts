import "@testing-library/jest-dom/vitest";
import { webcrypto } from "node:crypto";
import { afterEach, vi } from "vitest";
import { cleanup } from "@testing-library/react";
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
afterEach(() => { cleanup(); sessionStorage.clear(); vi.restoreAllMocks(); history.replaceState(null, "", "/"); });
