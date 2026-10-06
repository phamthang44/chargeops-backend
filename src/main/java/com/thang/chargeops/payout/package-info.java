/**
 * Legacy payout compatibility and financial history mappings.
 *
 * <p>Retained for reading existing V41/V45 data and migration verification.
 * The Simulator demo attributes receipts directly to the financial Owner;
 * it has no payout preview, execution, retry, cancellation, history API or
 * Owner bank-account onboarding. Payment/refund services must not depend on
 * this package or its eligibility, incident hold and adjustment projection.
 * Historical factories and repositories are retained for compatibility;
 * they do not authorize new application commands.
 */
package com.thang.chargeops.payout;
