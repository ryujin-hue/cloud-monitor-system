import type { Env } from "./types";

/**
 * CORS handling.
 *
 * Native Android apps do not send an `Origin` header, so the allow-list only matters for
 * browser based debugging. The allow-list is explicit: an unknown origin is never echoed
 * back.
 */
export function allowedOrigins(env: Env): string[] {
  return (env.ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter((value) => value.length > 0);
}

export function corsHeaders(request: Request, env: Env): Record<string, string> {
  const origin = request.headers.get("Origin");
  const headers: Record<string, string> = {
    Vary: "Origin",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, x-api-key",
    "Access-Control-Max-Age": "86400",
  };
  if (origin && allowedOrigins(env).includes(origin)) {
    headers["Access-Control-Allow-Origin"] = origin;
  }
  return headers;
}

export function jsonResponse(
  request: Request,
  env: Env,
  body: unknown,
  status = 200,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsHeaders(request, env),
      "Cache-Control": "no-store",
    },
  });
}

export function errorResponse(
  request: Request,
  env: Env,
  status: number,
  error: string,
  message: string,
): Response {
  return jsonResponse(request, env, { error, message }, status);
}

export function methodNotAllowed(request: Request, env: Env): Response {
  return errorResponse(request, env, 405, "method_not_allowed", "Use the documented method.");
}
