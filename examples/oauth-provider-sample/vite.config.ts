import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";

export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    proxy: Object.fromEntries(["/graphql", "/session", "/oauth", "/.well-known", "/demo-resource"].map(path => [
      path, { target: "http://127.0.0.1:10000", changeOrigin: false },
    ])),
  },
});
