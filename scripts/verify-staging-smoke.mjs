import { runStagingSmoke } from "./staging-smoke.mjs";

try {
  const verified = await runStagingSmoke(process.env);
  console.log(`Staging smoke: ${verified.length} read-only checks passed`);
  for (const name of verified) console.log(`- ${name}`);
} catch (error) {
  console.error(`Staging smoke failed: ${error.message}`);
  process.exitCode = 1;
}
