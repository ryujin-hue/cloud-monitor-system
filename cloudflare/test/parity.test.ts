import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, it } from "node:test";

import {
  ALL_ERROR_CODES,
  ERROR_CODE_OWNER,
  ERROR_CODE_SEVERITY,
  ERROR_CODE_TITLE,
  isErrorCode,
} from "../src/types";

/**
 * Locate the Kotlin source of truth.
 *
 * The Kotlin file lives in the `shared` module at the root of the monorepo, two levels
 * above the `cloudflare` directory. The lookup walks upwards so that the test keeps
 * working no matter where it is executed from.
 */
function findKotlinErrorCodes(): { file: string; codes: string[] } {
  let dir = __dirname;
  for (let i = 0; i < 6; i++) {
    const candidate = path.join(dir, "shared", "src", "main", "kotlin", "com", "cloudmonitor", "shared", "ErrorCode.kt");
    if (existsSync(candidate)) {
      const source = readFileSync(candidate, "utf8");
      // Every enum entry looks like: E_XXX(wire = "E-XXX", ...)
      const codes = [
        ...source.matchAll(/E_[A-Z0-9_]+\([\s\S]{0,120}?wire\s*=\s*"([^"]+)"/g),
      ].map((match) => match[1]);
      return { file: candidate, codes };
    }
    dir = path.dirname(dir);
  }
  throw new Error("could not locate shared/src/main/kotlin/com/cloudmonitor/shared/ErrorCode.kt");
}

/**
 * The error codes are defined ONCE in the Kotlin `shared` module. This test makes sure the
 * TypeScript mirror never drifts away from it.
 */
describe("error code parity between Kotlin and TypeScript", () => {
  const kotlin = findKotlinErrorCodes();

  it("finds the Kotlin source of truth", () => {
    assert.ok(kotlin.codes.length > 0, "no error codes found in the Kotlin file");
  });

  it("has exactly the same codes on both sides", () => {
    const kotlinCodes = [...kotlin.codes].sort();
    const tsCodes = [...ALL_ERROR_CODES].sort();
    assert.deepEqual(
      tsCodes,
      kotlinCodes,
      `the TypeScript mirror (${tsCodes.join(", ")}) differs from the Kotlin source ` +
        `(${kotlinCodes.join(", ")}) in ${kotlin.file}`,
    );
  });

  it("uses the expected set of codes", () => {
    assert.deepEqual(
      [...ALL_ERROR_CODES].sort(),
      ["E-101", "E-102", "E-204", "E-HB-01", "E-NET-01", "E-SHARE-01", "E-SHARE-02"],
    );
  });

  it("has a title, a severity and an owner for every code", () => {
    for (const code of ALL_ERROR_CODES) {
      assert.ok(ERROR_CODE_TITLE[code], `${code} has no title`);
      assert.ok(ERROR_CODE_SEVERITY[code], `${code} has no severity`);
      assert.ok(ERROR_CODE_OWNER[code], `${code} has no owner`);
    }
  });

  it("lets only the backend originate E-HB-01", () => {
    assert.equal(ERROR_CODE_OWNER["E-HB-01"], "BACKEND");
    for (const code of ALL_ERROR_CODES) {
      if (code === "E-HB-01") continue;
      assert.equal(ERROR_CODE_OWNER[code], "MONITOR", `${code} must be monitor owned`);
    }
  });

  it("recognises unknown codes", () => {
    assert.equal(isErrorCode("E-101"), true);
    assert.equal(isErrorCode("E-999"), false);
    assert.equal(isErrorCode("e-101"), false);
  });
});
