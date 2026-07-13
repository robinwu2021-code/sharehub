import { defineConfig } from "vitest/config";
import path from "node:path";

// 仅测 lib 纯函数（nav/permissions 无 React/DOM 依赖），不需要 jsdom。
export default defineConfig({
  test: {
    include: ["lib/**/*.test.ts"],
    environment: "node",
  },
  resolve: {
    alias: { "@": path.resolve(__dirname) },
  },
});
