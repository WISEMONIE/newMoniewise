-- V16: Clear the provider_reference on companion FEE transaction logs.
--
-- Root cause of the P2P revenue-fee bug:
--   When an envelope external transfer is initiated, BOTH the main EXT-... log
--   AND its EXT-...-FEE companion were stored with the same provider_reference
--   (the Rubies NIP session ID returned by the fund-transfer API response).
--
--   When the Rubies DR SUCCESS webhook arrived with paymentReference = sessionId,
--   settleExternalTransferIfExists called findByProviderReference(sessionId),
--   which found 2 rows instead of 1 and threw IncorrectResultSizeDataAccessException.
--   The webhook handler returned HTTP 500, Rubies retried, same error, eventually
--   stopped retrying — leaving ALL EXT- TransactionLogs stuck in PROCESSING forever.
--
-- Fix: FEE logs are looked up by reference ("EXT-UUID-FEE"), not by provider_reference.
--      They do not need a provider_reference at all.
--
-- Application-side fix: EnvelopeService no longer sets providerReference on feeTxn.
-- This migration cleans up the existing bad rows so that any outstanding webhook
-- retries (or manual re-settlements via POST /admin/transfers/settle/{ref}) now work.

UPDATE transaction_logs
SET    provider_reference = NULL
WHERE  reference LIKE '%-FEE'
  AND  provider_reference IS NOT NULL;
