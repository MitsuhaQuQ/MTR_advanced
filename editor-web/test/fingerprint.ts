/**
 * A short fingerprint of a large value for a snapshot (all of every service on every weekday would be megabytes): its
 * size and a hash of its JSON, Maps and Sets as lists. Any change shows as a different hash, though not where it is; to
 * see that, snapshot the value itself for a moment.
 */
import { createHash } from "node:crypto"

export function fingerprint(value: unknown) {
  const json = JSON.stringify(value, (_key, v) => (v instanceof Map ? [...v] : v instanceof Set ? [...v] : v))
  return { length: json.length, sha256: createHash("sha256").update(json).digest("hex").slice(0, 32) }
}
