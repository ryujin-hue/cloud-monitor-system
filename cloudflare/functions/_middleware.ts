import { corsHeaders } from "../src/cors";
import type { Env } from "../src/types";

/**
 * Cross-cutting middleware for every API route.
 *
 * It answers CORS preflight requests and adds the CORS headers to the real responses.
 */
export const onRequest = async (
  { request, next, env }: { request: Request; next: () => Promise<Response>; env: Env },
): Promise<Response> => {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders(request, env) });
  }

  const response = await next();
  const headers = new Headers(response.headers);
  for (const [key, value] of Object.entries(corsHeaders(request, env))) {
    headers.set(key, value);
  }
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
};
