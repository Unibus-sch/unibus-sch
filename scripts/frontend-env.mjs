export const REQUIRED_FRONTEND_ENVIRONMENT = [
  "VITE_SUPABASE_URL",
  "VITE_SUPABASE_ANON_KEY",
  "VITE_PUBLIC_API_BASE_URL",
  "VITE_AUTH_API_BASE_URL",
  "VITE_ADMIN_API_BASE_URL",
  "VITE_DRIVER_API_BASE_URL",
];

const URL_ENVIRONMENT = REQUIRED_FRONTEND_ENVIRONMENT.filter((name) => name.endsWith("_URL"));

export function validateFrontendEnvironment(environment) {
  const errors = [];
  for (const name of REQUIRED_FRONTEND_ENVIRONMENT) {
    if (!environment[name]?.trim()) errors.push(`${name} is required`);
  }

  for (const name of URL_ENVIRONMENT) {
    const rawValue = environment[name]?.trim();
    if (!rawValue) continue;
    try {
      const url = new URL(rawValue);
      if (!["http:", "https:"].includes(url.protocol)) {
        errors.push(`${name} must use http or https`);
      }
      if (url.username || url.password) {
        errors.push(`${name} must not contain credentials`);
      }
      if (isProductionDeployment(environment) && isLoopback(url.hostname)) {
        errors.push(`${name} must not use a loopback host in a production deployment`);
      }
    } catch {
      errors.push(`${name} must be a valid absolute URL`);
    }
  }

  if (errors.length > 0) {
    throw new Error(`Frontend environment validation failed:\n- ${errors.join("\n- ")}`);
  }
}

function isProductionDeployment(environment) {
  return environment.VERCEL_ENV === "production"
    || environment.UNIBUS_DEPLOYMENT_ENV === "production";
}

function isLoopback(hostname) {
  return ["localhost", "127.0.0.1", "::1"].includes(hostname);
}
