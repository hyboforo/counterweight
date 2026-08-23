/**
 * Which till this machine is.
 *
 * Remembered per machine because nothing asks the operator for it — there is
 * no drawer to open and no shift to start, so a sale carries the till code
 * purely so a receipt can be routed back to the printer sitting next to it.
 */
const TILL_KEY = "cw.till";

export function tillCode(): string {
  return localStorage.getItem(TILL_KEY) ?? "TILL-1";
}

export function setTillCode(code: string): void {
  localStorage.setItem(TILL_KEY, code);
}
