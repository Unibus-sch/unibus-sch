import { loadEnv } from "vite";

import { validateFrontendEnvironment } from "./frontend-env.mjs";

const mode = process.env.VITE_MODE || "production";
const environment = {
  ...loadEnv(mode, process.cwd(), ""),
  ...process.env,
};

try {
  validateFrontendEnvironment(environment);
  console.log("Frontend environment: verified");
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
