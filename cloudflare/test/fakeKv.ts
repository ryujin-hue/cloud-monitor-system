import type { Env } from "../src/types";

/**
 * A minimal in-memory Workers KV that is good enough for the backend.
 *
 * It implements exactly the parts of the `KVNamespace` interface the backend uses:
 * `get`, `put`, `delete` and `list`. The behaviour that matters for the tests is the
 * *eventual consistency* of a real KV namespace, which the fake models with an optional
 * delay: a key that was just written is not visible to `list` until `visibilityDelayMs`
 * has passed. That is exactly the race that cost the device registry in production.
 */
export class FakeKv {
  private readonly store = new Map<string, string>();
  private readonly listVisibleAt = new Map<string, number>();

  constructor(private readonly options: { visibilityDelayMs?: number } = {}) {}

  /** Make a key visible to `list` right away (no propagation delay). */
  makeVisible(key: string): void {
    this.listVisibleAt.delete(key);
  }

  async get(key: string, type?: string): Promise<unknown> {
    const raw = this.store.get(key);
    if (raw === undefined) return null;
    if (type === "json") return JSON.parse(raw);
    return raw;
  }

  async put(key: string, value: string): Promise<void> {
    this.store.set(key, value);
    this.listVisibleAt.set(key, Date.now() + (this.options.visibilityDelayMs ?? 0));
  }

  async delete(key: string): Promise<void> {
    this.store.delete(key);
    this.listVisibleAt.delete(key);
  }

  async list(options?: { prefix?: string | null; cursor?: string | null }): Promise<{
    list_complete: boolean;
    keys: Array<{ name: string }>;
    cursor: string;
    cacheStatus: string | null;
  }> {
    const prefix = options?.prefix ?? "";
    const now = Date.now();
    const keys = [...this.store.keys()]
      .filter((key) => key.startsWith(prefix))
      .filter((key) => (this.listVisibleAt.get(key) ?? 0) <= now)
      .sort()
      .map((name) => ({ name }));
    return { list_complete: true, keys, cursor: "", cacheStatus: null };
  }

  /** Test helper: the raw value of a key. */
  peek(key: string): string | undefined {
    return this.store.get(key);
  }

  /** Test helper: all keys, ignoring the list visibility delay. */
  rawKeys(): string[] {
    return [...this.store.keys()].sort();
  }
}

/** Build an `Env` that uses the fake KV. */
export function fakeEnv(kv: FakeKv, extra: Partial<Env> = {}): Env {
  return {
    KV: kv as unknown as KVNamespace,
    MONITOR_API_KEY: "monitor-test-key",
    ALERT_API_KEY: "alert-test-key",
    ...extra,
  } as Env;
}
