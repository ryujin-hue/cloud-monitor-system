import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { readEvents } from "../../src/state";
import type { Env, MonitorEvent } from "../../src/types";

/**
 * `GET /api/events` — the event history, filterable by device and/or error code.
 *
 * Query parameters:
 *   * `deviceId` — only events of this cloud phone,
 *   * `code`     — only events with this error code,
 *   * `since`    — only events that occurred after this epoch millis,
 *   * `limit`    — maximum number of events (default 200, max 1000).
 */
export const onRequestGet = async ({ request, env }: { request: Request; env: Env }) => {
  if (!isAuthorized(request, env, "alert")) {
    return errorResponse(request, env, 401, "unauthorized", "Invalid or missing x-api-key.");
  }

  const url = new URL(request.url);
  const deviceId = url.searchParams.get("deviceId");
  const code = url.searchParams.get("code");
  const sinceParam = url.searchParams.get("since");
  const limitParam = url.searchParams.get("limit");

  const since = sinceParam ? Number.parseInt(sinceParam, 10) : undefined;
  const limit = Math.min(Math.max(Number.parseInt(limitParam ?? "200", 10) || 200, 1), 1000);

  const stored = await readEvents(env);
  let events: MonitorEvent[] = stored;

  if (deviceId) events = events.filter((event) => event.deviceId === deviceId);
  if (code) events = events.filter((event) => event.code === code);
  if (since !== undefined && Number.isFinite(since)) {
    events = events.filter((event) => event.occurredAt >= since);
  }

  events = events.slice().sort((a, b) => b.occurredAt - a.occurredAt).slice(0, limit);

  return jsonResponse(request, env, {
    events,
    nextCursor: null,
  });
};

export const onRequestPost = async ({ request, env }: { request: Request; env: Env }) =>
  methodNotAllowed(request, env);
