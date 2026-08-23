/**
 * The permissions somebody can be asked to approve with a till PIN.
 *
 * Taken from the three call sites in `CartService` and `SaleCompletionService`
 * rather than from a role name: an override is re-evaluated under the
 * approver's own roles, so whoever holds one of these is somebody a cashier may
 * turn to, and whoever holds none will never be asked for a PIN at all.
 *
 * SALE_DISCOUNT is on the list and sales staff hold it — a discount beyond one
 * person's allowance can be approved by anybody whose allowance covers it.
 */
export const APPROVAL_PERMISSIONS = [
  "SALE_DISCOUNT",
  "SALE_PRICE_OVERRIDE",
  "CREDIT_APPROVE",
];
