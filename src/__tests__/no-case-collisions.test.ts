import { readdirSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Guard against module paths that differ only by case (e.g. `SnapshotBucket.tsx`
 * vs `snapshotBucket.ts`). On a case-insensitive filesystem — WSL's `/mnt/c`,
 * macOS by default — an extensionless import like `./SnapshotBucket` resolves to
 * whichever of those two Vite's `resolve.extensions` order hits first, silently
 * loading the wrong module. Case-sensitive CI never sees it, so the app builds
 * green yet renders a blank screen locally. This test makes that class of bug
 * fail in CI too.
 *
 * Resolution is on the *base name* (extension stripped), so the comparison must
 * strip the resolvable extension before lower-casing — `foo.ts` + `foo.tsx`
 * (same base, same case) is deterministic everywhere and fine; `Foo.tsx` +
 * `foo.ts` is not.
 */
const ROOT = process.cwd();
const SCAN_ROOTS = ["src", "mock-ha", "e2e"];
const SKIP = new Set(["node_modules", ".git", "dist", "coverage", "test-results", "playwright-report"]);
// Vite/TS resolvable extensions — order mirrors the default `resolve.extensions`.
const RESOLVABLE = [".mjs", ".js", ".mts", ".ts", ".jsx", ".tsx", ".json"];

/** Strip a single resolvable extension so we compare what a bare import resolves on. */
function baseName(name: string): string {
  const ext = RESOLVABLE.find((e) => name.endsWith(e));
  return ext ? name.slice(0, -ext.length) : name;
}

function collisionsIn(dir: string): string[] {
  const found: string[] = [];
  // lower-cased base -> set of the distinct case-sensitive bases seen.
  const groups = new Map<string, Set<string>>();
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const base = baseName(entry.name);
    const key = base.toLowerCase();
    (groups.get(key) ?? groups.set(key, new Set()).get(key)!).add(base);
    if (entry.isDirectory() && !SKIP.has(entry.name)) {
      found.push(...collisionsIn(join(dir, entry.name)));
    }
  }
  for (const [, bases] of groups) {
    if (bases.size > 1) found.push(`${relative(ROOT, dir)}/{${[...bases].join(" , ")}}`);
  }
  return found;
}

describe("filesystem", () => {
  it("has no module paths that differ only by case (breaks case-insensitive dev/macOS)", () => {
    const collisions = SCAN_ROOTS.flatMap((r) => collisionsIn(join(ROOT, r)));
    expect(collisions, `case-only path collisions:\n${collisions.join("\n")}`).toEqual([]);
  });
});
