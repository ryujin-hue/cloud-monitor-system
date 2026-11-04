import type { Env } from "./types";

/**
 * Firebase Cloud Messaging, HTTP v1 API.
 *
 * There is NO `firebase-admin` dependency: the service account JWT is signed with
 * WebCrypto (available in the Workers runtime) and exchanged for an OAuth access token
 * directly against Google's token endpoint.
 */

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

const TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

export interface SendResult {
  ok: boolean;
  /** The token is no longer valid and must be removed. */
  unregistered: boolean;
  error?: string;
}

function base64UrlEncode(input: ArrayBuffer | Uint8Array | string): string {
  let bytes: Uint8Array;
  if (typeof input === "string") {
    bytes = new TextEncoder().encode(input);
  } else if (input instanceof Uint8Array) {
    bytes = input;
  } else {
    bytes = new Uint8Array(input);
  }
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Convert a PEM encoded PKCS#8 private key into the DER bytes WebCrypto expects. */
export function pemToDer(pem: string): Uint8Array {
  const body = pem
    .replace(/-----BEGIN PRIVATE KEY-----/g, "")
    .replace(/-----END PRIVATE KEY-----/g, "")
    .replace(/-----BEGIN RSA PRIVATE KEY-----/g, "")
    .replace(/-----END RSA PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

async function signJwt(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claims = {
    iss: sa.client_email,
    scope: SCOPE,
    aud: TOKEN_ENDPOINT,
    iat: now,
    exp: now + 3600,
  };
  const signingInput = `${base64UrlEncode(JSON.stringify(header))}.${base64UrlEncode(
    JSON.stringify(claims),
  )}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(signingInput),
  );
  return `${signingInput}.${base64UrlEncode(new Uint8Array(signature))}`;
}

/** Cached OAuth access token. The module scope survives across requests in an isolate. */
let cachedToken: { token: string; expiresAt: number } | null = null;

export function parseServiceAccount(raw: string | undefined): ServiceAccount | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as ServiceAccount;
    if (!parsed.project_id || !parsed.client_email || !parsed.private_key) return null;
    return parsed;
  } catch {
    return null;
  }
}

async function getAccessToken(sa: ServiceAccount): Promise<string> {
  if (cachedToken && cachedToken.expiresAt > Date.now() + 60_000) {
    return cachedToken.token;
  }
  const assertion = await signJwt(sa);
  const response = await fetch(TOKEN_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`token endpoint returned ${response.status}: ${await response.text()}`);
  }
  const data = (await response.json()) as { access_token: string; expires_in: number };
  cachedToken = {
    token: data.access_token,
    expiresAt: Date.now() + (data.expires_in ?? 3600) * 1000,
  };
  return data.access_token;
}

/** Is push delivery configured at all? */
export function isPushConfigured(env: Env): boolean {
  if ((env.PUSH_ENABLED ?? "true").toLowerCase() === "false") return false;
  return parseServiceAccount(env.FCM_SERVICE_ACCOUNT_JSON) !== null;
}

/**
 * Send a DATA-ONLY message.
 *
 * Data-only means the notification is built by the app, not by Firebase: the wording, the
 * channel and the deep link stay under our control, and no status data ends up in Firebase.
 */
export async function sendDataMessage(
  env: Env,
  token: string,
  data: Record<string, string>,
): Promise<SendResult> {
  const sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT_JSON);
  if (!sa) {
    return { ok: false, unregistered: false, error: "FCM is not configured" };
  }

  try {
    const accessToken = await getAccessToken(sa);
    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          message: {
            token,
            // High priority + data only: the app wakes up and renders the notification.
            data,
            android: {
              priority: "high",
              // Do not let the message be dropped when the device is in Doze.
              ttl: "0s",
            },
          },
        }),
      },
    );

    if (response.ok) return { ok: true, unregistered: false };

    const body = await response.text();
    const unregistered =
      body.includes("UNREGISTERED") ||
      body.includes("NOT_FOUND") ||
      (response.status === 400 && body.includes("INVALID_ARGUMENT"));
    return {
      ok: false,
      unregistered,
      error: `FCM returned ${response.status}: ${body.slice(0, 300)}`,
    };
  } catch (error) {
    return { ok: false, unregistered: false, error: String(error) };
  }
}
