import { readSubscriptions, writeSubscriptions } from "./state";
import { isPushConfigured, sendDataMessage } from "./fcm";
import { errorSeverity, errorTitle, type Env, type IncidentRecord, type MonitorEvent } from "./types";

/**
 * Push delivery with de-duplication and a cooldown.
 *
 * Two guards prevent a storm of notifications:
 *
 *  * **de-duplication**: the same `eventId` is never pushed twice, even if the event is
 *    delivered again from the cloud phone's offline queue,
 *  * **cooldown**: an incident is not pushed again within `PUSH_COOLDOWN_MS`, so a flapping
 *    app cannot spam the alert phone.
 */

export const PUSH_COOLDOWN_MS = 60_000;

export interface PushOutcome {
  attempted: number;
  sent: number;
  removedTokens: number;
  skipped: boolean;
  reason?: string;
}

/** Build the human readable text of an alert. */
export function describeEvent(event: MonitorEvent, deviceLabel: string): {
  title: string;
  message: string;
} {
  const title = errorTitle(event.code);
  const target = event.packageName ? ` (${event.packageName})` : "";
  switch (event.type) {
    case "DOWN":
      return {
        title,
        message: `${deviceLabel || event.deviceId}: ${title}${target}`,
      };
    case "RECOVERED":
      return {
        title: `${title} — recovered`,
        message: `${deviceLabel || event.deviceId}: ${title}${target} is back`,
      };
    default:
      return {
        title,
        message: `${deviceLabel || event.deviceId}: ${title}${target}`,
      };
  }
}

/**
 * Push an event to every alert device that is subscribed to the affected cloud phone.
 */
export async function pushEvent(
  env: Env,
  event: MonitorEvent,
  incident: IncidentRecord | null,
  deviceLabel: string,
): Promise<PushOutcome> {
  if (!isPushConfigured(env)) {
    return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "push disabled" };
  }

  const now = Date.now();
  if (incident) {
    if (incident.lastPushEventId === event.eventId) {
      return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "duplicate" };
    }
    if (incident.lastPushAt !== undefined && now - incident.lastPushAt < PUSH_COOLDOWN_MS) {
      return { attempted: 0, sent: 0, removedTokens: 0, skipped: true, reason: "cooldown" };
    }
  }

  const subscriptions = await readSubscriptions(env);
  const targets = Object.values(subscriptions).filter((subscription) =>
    subscription.monitorDeviceIds.includes(event.deviceId),
  );

  let attempted = 0;
  let sent = 0;
  let removedTokens = 0;

  for (const subscription of targets) {
    for (const token of subscription.fcmTokens) {
      attempted += 1;
      const { title, message } = describeEvent(event, deviceLabel);
      const result = await sendDataMessage(env, token, {
        type: event.type,
        deviceId: event.deviceId,
        deviceLabel,
        code: event.code,
        packageName: event.packageName ?? "",
        eventId: event.eventId,
        occurredAt: String(event.occurredAt),
        receivedAt: String(event.receivedAt ?? now),
        title,
        message,
        severity: errorSeverity(event.code),
      });

      if (result.ok) {
        sent += 1;
      } else if (result.unregistered) {
        // The token is dead: remove it so we never try it again.
        subscription.fcmTokens = subscription.fcmTokens.filter((entry) => entry !== token);
        removedTokens += 1;
      }
    }
  }

  if (removedTokens > 0) {
    await writeSubscriptions(env, subscriptions);
  }

  return { attempted, sent, removedTokens, skipped: false };
}
