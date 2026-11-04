import type { Env } from "./types";

/**
 * Constant-time string comparison.
 *
 * The loop always walks the whole string, so the execution time does not depend on how many
 * leading characters matched.
 */
export function constantTimeEquals(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  const left = encoder.encode(a);
  const right = encoder.encode(b);

  // Different lengths are detected without an early exit on the content.
  let diff = left.length ^ right.length;
  const length = Math.max(left.length, right.length);
  for (let i = 0; i < length; i++) {
    diff |= (left[i] ?? 0) ^ (right[i] ?? 0);
  }
  return diff === 0;
}

export type ApiScope = "monitor" | "alert";

/**
 * Check the `x-api-key` header for the given scope.
 *
 * The monitor key and the alert key are separate secrets, so a leaked alert key can never
 * post events and a leaked monitor key can never read the status of the fleet.
 */
export function isAuthorized(request: Request, env: Env, scope: ApiScope): boolean {
  const header = request.headers.get("x-api-key");
  if (!header) return false;

  const expected =
    scope === "monitor" ? env.MONITOR_API_KEY : env.ALERT_API_KEY;
  if (!expected) {
    // Fail closed: an unconfigured key never authorises anything.
    return false;
  }
  return constantTimeEquals(header, expected);
}
