import { isAuthorized } from "../../src/auth";
import { errorResponse, jsonResponse, methodNotAllowed } from "../../src/cors";
import { readDevices, readSubscriptions, writeSubscriptions } from "../../src/state";
import type { Env, RegisterRequest, RegisterResponse } from "../../src/types";

/**
 * `POST /api/register` — register this Cloud Alert device and (optionally) pair it with a
 * cloud phone.
 *
 * The endpoint serves two purposes, distinguished by the presence of `pairingCode`:
 *
 *   * without a pairing code: register / refresh the alert device and its FCM token,
 *   * with a pairing code: additionally subscribe to the cloud phone that currently shows
 *     that 6-digit code.
 */
export const onRequestPost = async ({
  request,
  env,
}: {
  request: Request;
  env: Env;
}): Promise<Response> => {
  if (!isAuthorized(request, env, "alert")) {
    return errorResponse(request, env, 401, "unauthorized", "Invalid or missing x-api-key.");
  }

  let body: RegisterRequest;
  try {
    body = (await request.json()) as RegisterRequest;
  } catch {
    return errorResponse(request, env, 400, "invalid_json", "The body is not valid JSON.");
  }

  if (!body || typeof body.alertDeviceId !== "string" || !body.alertDeviceId) {
    return errorResponse(request, env, 400, "invalid_request", "alertDeviceId is required.");
  }

  const now = Date.now();
  const subscriptions = await readSubscriptions(env);
  const devices = await readDevices(env);

  let subscription = subscriptions[body.alertDeviceId];
  if (!subscription) {
    subscription = {
      alertDeviceId: body.alertDeviceId,
      label: body.label ?? "",
      fcmTokens: [],
      monitorDeviceIds: [],
      updatedAt: now,
    };
    subscriptions[body.alertDeviceId] = subscription;
  }

  if (body.label !== undefined && body.label !== subscription.label) {
    subscription.label = body.label;
  }

  // A new FCM token replaces the old one for the same alert device: the token identifies
  // the installation, and a rotated token replaces the previous one.
  if (body.fcmToken) {
    subscription.fcmTokens = subscription.fcmTokens.filter((token) => token !== body.fcmToken);
    subscription.fcmTokens.push(body.fcmToken);
  }

  let message = "Registered.";
  if (body.pairingCode) {
    const code = body.pairingCode.trim();
    const match = Object.values(devices).find(
      (device) =>
        device.pairingCode === code &&
        (device.pairingExpiresAt ?? 0) > now,
    );
    if (match) {
      if (!subscription.monitorDeviceIds.includes(match.deviceId)) {
        subscription.monitorDeviceIds.push(match.deviceId);
      }
      message = `Paired with ${match.label || match.deviceId}.`;
    } else {
      message = "The pairing code is unknown or expired.";
    }
  }

  subscription.updatedAt = now;
  await writeSubscriptions(env, subscriptions);

  const response: RegisterResponse = {
    ok: true,
    message,
    subscribedDeviceIds: subscription.monitorDeviceIds,
    alertDeviceId: subscription.alertDeviceId,
  };
  return jsonResponse(request, env, response);
};

export const onRequestGet = async ({ request, env }: { request: Request; env: Env }) =>
  methodNotAllowed(request, env);
