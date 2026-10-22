package com.moniewise.moniewise_backend.psp.rubies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.WalletProvisioningException;
import com.moniewise.moniewise_backend.psp.PaymentGateway;
import com.moniewise.moniewise_backend.psp.WalletTransactionStatement;
import com.moniewise.moniewise_backend.psp.rubies.dto.*;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Rubies MFB BaaS integration — completely decoupled from Providus and SecureWave.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code RUBIES_API_KEY} env var — API key in Authorization header</li>
 *   <li>{@code RUBIES_BASE_URL} env var — base URL (default: https://api-sme-dev.rubies.ng)</li>
 *   <li>{@code system_config.rubies.stage} — "dev" or "prod" path parameter</li>
 * </ul>
 *
 * <h3>Switching to Rubies</h3>
 * Set {@code system_config.psp.active = RUBIES}. No code change required.
 *
 * <h3>Response codes</h3>
 * "00" = success, "09"/"90"/"99" = pending (poll TSQ), else = failed.
 */
@Component
public class RubiesGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "RUBIES";

    private static final Logger logger = LoggerFactory.getLogger(RubiesGateway.class);

    private final RestTemplate restTemplate;
    private final RestTemplate nameEnquiryRestTemplate;
    private final ObjectMapper objectMapper;
    private final SystemConfigService systemConfig;

    @Value("${rubies.api.key:}")
    private String apiKey;

    @Value("${rubies.base.url:https://api-sme-dev.rubies.ng}")
    private String baseUrl;

    /**
     * HMAC-SHA512 secret Rubies uses to sign webhook notifications.
     * Set {@code RUBIES_WEBHOOK_SECRET} env var (maps to {@code rubies.webhook.secret}).
     * While blank the gateway logs a security warning and accepts all webhooks —
     * tighten this before going live.
     */
    @Value("${rubies.webhook.secret:}")
    private String webhookSecret;

    // Optional: Rubies login credentials for auto-refresh when the JWT expires.
    // Set RUBIES_EMAIL + RUBIES_PASSWORD (+ optionally RUBIES_LOGIN_URL) env vars
    // to enable automatic JWT renewal on error code 22.
    @Value("${rubies.credentials.email:}")
    private String rubiesEmail;

    @Value("${rubies.credentials.password:}")
    private String rubiesPassword;

    @Value("${rubies.login.url:}")
    private String rubiesLoginUrl;

    // Volatile so all threads see the latest token after a refresh.
    private volatile String currentApiKey;
    private final ReentrantLock tokenRefreshLock = new ReentrantLock();

    public RubiesGateway(RestTemplate restTemplate,
                         @Qualifier("nameEnquiryRestTemplate") RestTemplate nameEnquiryRestTemplate,
                         ObjectMapper objectMapper,
                         SystemConfigService systemConfig) {
        this.restTemplate = restTemplate;
        this.nameEnquiryRestTemplate = nameEnquiryRestTemplate;
        this.objectMapper  = objectMapper;
        this.systemConfig  = systemConfig;
    }

    // ── Startup validation ────────────────────────────────────────────────────

    @PostConstruct
    public void validateConfiguration() {
        currentApiKey = apiKey;
        if (apiKey == null || apiKey.isBlank()) {
            logger.error("[Rubies] RUBIES_API_KEY (rubies.api.key) is not set — " +
                    "all Rubies API calls will fail with 401. Set the env var before starting.");
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.warn("[Rubies][SECURITY] RUBIES_WEBHOOK_SECRET (rubies.webhook.secret) is not set — " +
                    "webhook signature verification is DISABLED. Set the env var before going live.");
        }
        boolean canAutoRefresh = !isBlank(rubiesEmail) && !isBlank(rubiesPassword);
        if (!canAutoRefresh) {
            logger.warn("[Rubies] RUBIES_EMAIL / RUBIES_PASSWORD not set — JWT auto-refresh is DISABLED. " +
                    "When the token expires, update RUBIES_API_KEY on Render or call " +
                    "POST /admin/rubies/update-api-key with a fresh token.");
        } else {
            logger.info("[Rubies] Auto-refresh credentials are configured — JWT will be renewed automatically on expiry.");
        }
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Verifies BVN via Rubies and maps the flat Rubies response into the same
     * DTO shape SecureWave already feeds into the rest of the KYC flow.
     */
    public BvnVerificationResultDto verifyBvn(String bvn,
                                              String firstName,
                                              String lastName,
                                              String dob,
                                              String reference) {
        String cleanBvn = bvn != null ? bvn.trim() : "";
        String cleanFirstName = normalizePersonName(firstName);
        String cleanLastName = normalizePersonName(lastName);
        String cleanDob = dob != null ? dob.trim() : "";
        String cleanReference = reference != null ? reference.trim() : "";

        if (isBlank(cleanBvn) || isBlank(cleanFirstName) || isBlank(cleanLastName) || isBlank(cleanReference)) {
            throw new IllegalArgumentException("Rubies BVN validation requires bvn, firstName, lastName and reference.");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (isIsoDate(cleanDob)) {
            payload.put("dob", cleanDob);
        } else if (!isBlank(cleanDob)) {
            logger.info("[Rubies] Skipping non-ISO DOB for BVN validation fallback: reference={}", cleanReference);
        }
        payload.put("firstName", cleanFirstName);
        payload.put("lastName", cleanLastName);
        payload.put("bvn", cleanBvn);
        payload.put("reference", cleanReference);

        String url = baseUrl + "/" + stage() + "/baas-kyc/bvn-validation";
        logger.info("[Rubies] Verifying BVN via Rubies: reference={} bvn={}***",
                cleanReference, cleanBvn.substring(0, Math.min(4, cleanBvn.length())));

        try {
            return doBvnValidation(url, payload, cleanBvn, cleanReference, false);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] BVN validation exception: reference={} error={}",
                    cleanReference, e.getMessage(), e);
            throw new RuntimeException("Rubies BVN validation error: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private BvnVerificationResultDto doBvnValidation(String url,
                                                     Map<String, Object> payload,
                                                     String bvn,
                                                     String reference,
                                                     boolean isRetry) {
        ResponseEntity<Map> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(payload, authHeaders()),
                Map.class);

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new RuntimeException("Rubies BVN validation returned null response for reference=" + reference);
        }

        String responseCode = str(body, "responseCode", "");
        String responseMessage = str(body, "responseMessage", "BVN verification failed");

        // Code 22 = JWT expired. Reuse the same auto-refresh behaviour as transfers.
        if (!isRetry && "22".equals(responseCode)) {
            logger.warn("[Rubies] JWT expired (code 22) on BVN validation reference={}. Attempting token refresh.",
                    reference);
            if (tryRefreshToken()) {
                logger.info("[Rubies] Retrying BVN validation after JWT refresh: reference={}", reference);
                return doBvnValidation(url, payload, bvn, reference, true);
            }
        }

        if (!"00".equals(responseCode)) {
            logger.warn("[Rubies] BVN validation failed: reference={} code={} message={}",
                    reference, responseCode, responseMessage);
            throw new IllegalArgumentException("BVN verification failed: " + responseMessage);
        }

        logger.info("[Rubies] BVN validation successful: reference={}", reference);
        return mapRubiesBvnResult(body, bvn);
    }

    private BvnVerificationResultDto mapRubiesBvnResult(Map<String, Object> body, String fallbackBvn) {
        BvnVerificationResultDto dto = new BvnVerificationResultDto();

        dto.setBvnNumber(firstNonBlank(body, "bvn", "idNumber") != null
                ? firstNonBlank(body, "bvn", "idNumber")
                : fallbackBvn);
        dto.setNameOnCard(firstNonBlank(body, "nameOnCard", "fullName"));
        dto.setEnrolmentBank(firstNonBlank(body, "enrolmentBank", "enrollmentBank"));
        dto.setEnrolmentBranch(firstNonBlank(body, "enrolmentBranch", "enrollmentBranch"));
        dto.setFormattedRegistrationDate(firstNonBlank(body, "formattedRegistrationDate", "registrationDate"));
        dto.setLevelOfAccount(firstNonBlank(body, "levelOfAccount"));
        dto.setWatchlisted(firstNonBlank(body, "watchlisted", "watchListed"));
        dto.setVerificationStatus("VERIFIED");

        dto.setFirstName(firstNonBlank(body, "firstName", "firstname"));
        dto.setMiddleName(firstNonBlank(body, "middleName", "middlename"));
        dto.setLastName(firstNonBlank(body, "lastName", "lastname"));
        dto.setFullName(firstNonBlank(body, "fullName"));
        dto.setGender(firstNonBlank(body, "gender"));
        dto.setDateOfBirth(firstNonBlank(body, "dateOfBirth", "dob"));
        dto.setStateOfOrigin(firstNonBlank(body, "stateOfOrigin"));
        dto.setLgaOfOrigin(firstNonBlank(body, "lgaOfOrigin"));
        dto.setNationality(firstNonBlank(body, "nationality"));
        dto.setMaritalStatus(firstNonBlank(body, "maritalStatus"));
        dto.setStateOfResidence(firstNonBlank(body, "stateOfResidence"));
        dto.setLgaOfResidence(firstNonBlank(body, "lgaOfResidence"));
        dto.setResidentialAddress(firstNonBlank(body, "residentialAddress"));

        return dto;
    }

    // ── Wallet creation ───────────────────────────────────────────────────────

    /**
     * Creates a Rubies BaaS wallet for the user.
     *
     * <p>Called at the end of profile completion, after BVN has been verified
     * via SecureWave. The BVN name fields in {@code profileData} must match
     * the Rubies name-similarity threshold (~90%).
     *
     * @return map with keys: providerCustomerRef, providerWalletRef, accountNumber, bank
     */
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        String stage = stage();

        // Extract profile fields from JSONB profileData map
        Map<String, Object> profile = user.getProfileData() != null
                ? user.getProfileData() : Collections.emptyMap();

        String firstName = normalizePersonName(str(profile, "bvnFirstName", ""));
        String lastName  = normalizePersonName(str(profile, "bvnLastName",  ""));
        // dob must be in YYYY-MM-DD format — Rubies validates this against the BVN record
        String dob       = str(profile, "dateOfBirth", str(profile, "dob", ""));

        if (isBlank(firstName) || isBlank(lastName) || isBlank(dob)) {
            logger.warn("[Rubies] Missing verified BVN identity fields for wallet creation. user={} firstNamePresent={} lastNamePresent={} dobPresent={}",
                    user.getId(), !isBlank(firstName), !isBlank(lastName), !isBlank(dob));
            throw WalletProvisioningException.missingVerifiedKycData();
        }

        RubiesCreateWalletRequest req = RubiesCreateWalletRequest.forIndividualWallet(
                user.getBvn(),
                firstName,
                lastName,
                user.getEmail(),
                user.getPhone(),
                dob
        );

        String url = baseUrl + "/" + stage + "/baas-wallet/create-wallet";
        logger.info("[Rubies] Creating wallet for user={} at {}", user.getId(), url);

        try {
            ResponseEntity<RubiesCreateWalletResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesCreateWalletResponse.class);

            RubiesCreateWalletResponse body = response.getBody();

            if (body == null || !body.isSuccess()) {
                String msg = body != null ? body.getResponseMessage() : "null response";
                if (isKycIdentityMismatch(msg)) {
                    logger.warn("[Rubies] createVirtualAccount rejected KYC identity for user={}: {}", user.getId(), msg);
                    throw WalletProvisioningException.kycIdentityMismatch(msg);
                }

                logger.error("[Rubies] createVirtualAccount failed for user={}: {}", user.getId(), msg);
                throw WalletProvisioningException.providerUnavailable(msg);
            }

            // Rubies response is FLAT — no nested data wrapper
            logger.info("[Rubies] Wallet created for user={} → account={}", user.getId(), body.getAccountNumber());

            Map<String, String> result = new HashMap<>();
            result.put("providerCustomerRef", body.getCustomerId());
            result.put("providerWalletRef",   body.getAccountNumber());
            result.put("accountNumber",        body.getAccountNumber());
            result.put("accountName",          body.getAccountName());
            // Bank details are always fixed for Rubies wallets
            result.put("bank",                 "Rubies MFB");
            result.put("bankCode",             "090175");
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] createVirtualAccount exception for user={}: {}", user.getId(), e.getMessage(), e);
            throw new RuntimeException("Rubies wallet creation error: " + e.getMessage(), e);
        }
    }

    // ── Merchant / revenue wallet creation ───────────────────────────────────

    /**
     * Creates a Rubies BaaS wallet using raw fields instead of a {@link User} entity.
     *
     * <p>Used by the admin endpoint to create Moniewise's own Rubies revenue wallet.
     * The BVN and personal details must belong to an authorised company representative
     * (director, founder) — Rubies validates the BVN against NIBSS records.
     *
     * @return map with keys: accountNumber, accountName, customerId
     */
    public Map<String, String> createMerchantWallet(
            String bvn,
            String firstName,
            String lastName,
            String email,
            String phone,
            String dateOfBirth) {

        String stage = stage();
        String url   = baseUrl + "/" + stage + "/baas-wallet/create-wallet";

        // Revenue wallet is a business account. The prefix is compulsory for
        // individuals only, so this request leaves accountPrefix false.
        RubiesCreateWalletRequest req = new RubiesCreateWalletRequest(
                bvn, firstName, lastName, email, phone, dateOfBirth);

        logger.info("[Rubies] Creating merchant/revenue wallet: email={} at {}", email, url);

        try {
            ResponseEntity<RubiesCreateWalletResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesCreateWalletResponse.class);

            RubiesCreateWalletResponse body = response.getBody();

            if (body == null || !body.isSuccess()) {
                String msg = body != null ? body.getResponseMessage() : "null response";
                logger.error("[Rubies] createMerchantWallet failed: {}", msg);
                throw new RuntimeException("Rubies merchant wallet creation failed: " + msg);
            }

            logger.info("[Rubies] Merchant wallet created: accountNumber={} name={}",
                    body.getAccountNumber(), body.getAccountName());

            Map<String, String> result = new HashMap<>();
            result.put("accountNumber", body.getAccountNumber());
            result.put("accountName",   body.getAccountName());
            result.put("customerId",    body.getCustomerId());
            result.put("bank",          "Rubies MFB");
            result.put("bankCode",      "090175");
            return result;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] createMerchantWallet exception: {}", e.getMessage(), e);
            throw new RuntimeException("Rubies merchant wallet creation error: " + e.getMessage(), e);
        }
    }

    // ── Name enquiry (account resolve) ───────────────────────────────────────

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        String url = baseUrl + "/" + stage() + "/baas-transaction/name-enquiry";
        RubiesNameEnquiryRequest req = new RubiesNameEnquiryRequest(bankCode, accountNumber);

        logger.debug("[Rubies] Name enquiry: bank={} acct={}", bankCode, accountNumber);

        try {
            ResponseEntity<RubiesNameEnquiryResponse> response =
                    nameEnquiryRestTemplate.exchange(
                            url,
                            HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesNameEnquiryResponse.class
                    );

            RubiesNameEnquiryResponse body = response.getBody();
            if (body == null || !"00".equals(body.getResponseCode())) {
                throw new RuntimeException("Rubies failed: " + body.getResponseMessage());
            }

            return body.getAccountName();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] resolveAccount exception: {}", e.getMessage(), e);
            throw new RuntimeException("Rubies name enquiry error: " + e.getMessage(), e);
        }
    }

    // ── Bank list ─────────────────────────────────────────────────────────────

    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        String url = baseUrl + "/" + stage() + "/baas-transaction/bank-list";
        RubiesBankListRequest req = new RubiesBankListRequest();

        logger.debug("[Rubies] Fetching bank list");

        try {
            ResponseEntity<RubiesBankListResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesBankListResponse.class);

            RubiesBankListResponse body = response.getBody();

            if (body == null || !body.isSuccess() || body.getData() == null) {
                logger.warn("[Rubies] getSupportedBanks returned non-success: {}",
                        body != null ? body.getResponseMessage() : "null");
                return Collections.emptyList();
            }

            // Map to the canonical shape Flutter's Bank.fromJson expects:
            //   { "id": int, "name": String, "bank_code": String }
            // This must match the static NIGERIAN_BANK_FALLBACK shape in WalletService.
            AtomicInteger idx = new AtomicInteger(1);
            return body.getData().stream()
                    .filter(entry -> entry.getBankName() != null && entry.getBankCode() != null)
                    .map(entry -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id",        idx.getAndIncrement());
                        m.put("name",      entry.getBankName());
                        m.put("bank_code", entry.getBankCode());
                        return (Map<String, Object>) m;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("[Rubies] getSupportedBanks exception: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Fund transfer (with full context) ────────────────────────────────────

    /**
     * Sends funds via Rubies NIP transfer.
     *
     * <p>This is the primary method — always prefer this over the fallback
     * {@link #initiateTransfer} when the debit account details are available.
     *
     * <p><strong>amount is passed as STRING per Rubies spec.</strong>
     */
    @Override
    public String initiateTransferWithContext(
            String debitAccountNumber, String debitAccountName,
            String creditBankCode, String creditBankName,
            String creditAccountNumber, String creditAccountName,
            BigDecimal amount, String reference, String narration) {

        String url = baseUrl + "/" + stage() + "/baas-transaction/fund-transfer";

        // Rubies requires amount as a plain numeric string
        String amountStr = amount.toPlainString();

        RubiesFundTransferRequest req = new RubiesFundTransferRequest(
                debitAccountNumber, debitAccountName,
                creditBankCode, creditBankName,
                creditAccountNumber, creditAccountName,
                amountStr, reference, narration
        );

        ObjectMapper mapper = new ObjectMapper();
        try {
            logger.info("RUBIES REQUEST >>> {}", mapper.writeValueAsString(req));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        logger.info("[Rubies] Fund transfer: ref={} amount={} from={} to={}/{}",
                reference, amountStr, debitAccountNumber, creditBankCode, creditAccountNumber);

        try {
            return doFundTransfer(url, req, reference, false);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[Rubies] Fund transfer exception: ref={} error={}", reference, e.getMessage(), e);
            throw new RuntimeException("Rubies transfer error: " + e.getMessage(), e);
        }
    }

    /**
     * Executes the fund-transfer HTTP call.  If Rubies returns error code 22 (JWT expired)
     * and this is the first attempt, we refresh the token and retry exactly once.
     */
    private String doFundTransfer(String url, RubiesFundTransferRequest req,
                                  String reference, boolean isRetry) {
        ResponseEntity<RubiesFundTransferResponse> response =
                restTemplate.exchange(url, HttpMethod.POST,
                        new HttpEntity<>(req, authHeaders()),
                        RubiesFundTransferResponse.class);

        RubiesFundTransferResponse body = response.getBody();

        if (body == null) {
            throw new RuntimeException("Rubies fund transfer returned null response for ref=" + reference);
        }

        // Code 22 = JWT expired. Refresh and retry once — but never on a retry to avoid loops.
        if (!isRetry && "22".equals(body.getResponseCode())) {
            logger.warn("[Rubies] JWT expired (code 22) on fund transfer ref={}. Attempting token refresh.", reference);
            if (tryRefreshToken()) {
                logger.info("[Rubies] Retrying fund transfer after JWT refresh: ref={}", reference);
                return doFundTransfer(url, req, reference, true);
            }
            // Refresh failed or not configured — fall through and throw the original error
        }

        if (body.isFailed()) {
            logger.error("[Rubies] Fund transfer FAILED: ref={} code={} msg={}",
                    reference, body.getResponseCode(), body.getResponseMessage());
            throw new RuntimeException("Rubies transfer failed [" + body.getResponseCode() + "]: "
                    + body.getResponseMessage());
        }

        // "00" = success, "09"/"90"/"99" = pending — both are acceptable outcomes here.
        // The caller/webhook handler will finalize on pending.
        // NOTE: FundTransferResponse is FLAT per the official Rubies production schema
        // (no "data" wrapper) — read sessionId directly off the body, falling back to
        // our locally-generated reference only if Rubies didn't echo one back.
        String sessionId = (body.getSessionId() != null && !body.getSessionId().isBlank())
                ? body.getSessionId()
                : reference;
        logger.info("[Rubies] Fund transfer {}: ref={} sessionId={}",
                body.isSuccess() ? "SUCCESS" : "PENDING", reference, sessionId);

        return sessionId != null ? sessionId : reference;
    }

    /**
     * Fallback — called by {@link PaymentGateway#initiateTransferWithContext} default
     * and by any code that calls the old interface directly.
     *
     * <p>Without a {@code debitAccountNumber} we cannot build a valid Rubies request.
     * This should never be invoked in normal flow — log a warning and throw so the
     * caller is forced to use {@link #initiateTransferWithContext}.
     */
    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName,
                                   BigDecimal amount, String uniqueReference, String narration) {
        logger.error("[Rubies] initiateTransfer called without debitAccountNumber — " +
                "use initiateTransferWithContext instead. ref={}", uniqueReference);
        throw new UnsupportedOperationException(
                "Rubies requires debitAccountNumber. Use initiateTransferWithContext.");
    }

    // ── Balance enquiry ───────────────────────────────────────────────────────

    @Override
    public Optional<BigDecimal> fetchWalletBalance(String walletReference) {
        String url = baseUrl + "/" + stage() + "/baas-wallet/balance-enquiry";
        RubiesBalanceEnquiryRequest req = new RubiesBalanceEnquiryRequest(walletReference);

        logger.debug("[Rubies] Balance enquiry: acct={}", walletReference);

        try {
            ResponseEntity<RubiesBalanceEnquiryResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesBalanceEnquiryResponse.class);

            RubiesBalanceEnquiryResponse body = response.getBody();

            if (body == null || !body.isSuccess() || body.getData() == null) {
                return Optional.empty();
            }

            return Optional.of(body.getData().availableBalanceDecimal());

        } catch (Exception e) {
            logger.warn("[Rubies] fetchWalletBalance exception for acct={}: {}", walletReference, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Transaction status query ──────────────────────────────────────────────

    @Override
    public Optional<List<Map<String, Object>>> fetchAllWalletTransactions(
            String startDate, String endDate, int page, int pageSize) {
        return fetchAllWalletTransactions(startDate, endDate, null, page, pageSize);
    }

    @Override
    public Optional<List<Map<String, Object>>> fetchAllWalletTransactions(
            String startDate, String endDate, String searchItem, int page, int pageSize) {
        String url = baseUrl + "/" + stage() + "/baas-wallet/read-all-wallet-transactions";
        int currentPage = Math.max(1, page);
        int safePageSize = pageSize > 0 ? pageSize : 100;
        int pageGuard = 0;
        List<Map<String, Object>> transactions = new ArrayList<>();

        while (pageGuard++ < 50) {
            RubiesReadAllTransactionsResponse body = fetchWalletTransactionsPage(
                    url,
                    new RubiesReadAllTransactionsRequest(
                            startDate, endDate, searchItem, currentPage, safePageSize),
                    false
            );

            if (body == null || !body.isSuccess()) {
                return Optional.empty();
            }

            if (body.getData() != null) {
                transactions.addAll(body.getData());
            }

            if (!body.isHasNext()) {
                break;
            }
            currentPage++;
        }

        if (pageGuard >= 50) {
            logger.warn("[Rubies] Stopped wallet transaction pagination after 50 pages for searchItem={}", searchItem);
        }

        return Optional.of(transactions);
    }

    /**
     * Same pages as {@link #fetchAllWalletTransactions}, plus a truncation flag
     * so a reconciliation cursor is not moved past a window that was cut short.
     * A failed first page is empty. A later failed page keeps the lines already
     * read and marks them truncated.
     */
    @Override
    public Optional<WalletTransactionStatement> fetchWalletTransactionStatement(
            String startDate, String endDate, String searchItem, int page, int pageSize) {
        String url = baseUrl + "/" + stage() + "/baas-wallet/read-all-wallet-transactions";
        int currentPage = Math.max(1, page);
        int safePageSize = pageSize > 0 ? pageSize : 100;
        int pagesRead = 0;
        List<Map<String, Object>> transactions = new ArrayList<>();

        while (pagesRead < 50) {
            pagesRead++;
            RubiesReadAllTransactionsResponse body = fetchWalletTransactionsPage(
                    url,
                    new RubiesReadAllTransactionsRequest(
                            startDate, endDate, searchItem, currentPage, safePageSize),
                    false);
            if (body == null || !body.isSuccess()) {
                if (transactions.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new WalletTransactionStatement(transactions, true));
            }
            if (body.getData() != null) {
                transactions.addAll(body.getData());
            }
            if (!body.isHasNext()) {
                return Optional.of(new WalletTransactionStatement(transactions, false));
            }
            currentPage++;
        }
        logger.warn("[Rubies] Stopped revenue statement pagination after 50 pages");
        return Optional.of(new WalletTransactionStatement(transactions, true));
    }

    private RubiesReadAllTransactionsResponse fetchWalletTransactionsPage(
            String url,
            RubiesReadAllTransactionsRequest req,
            boolean isRetry) {
        try {
            ResponseEntity<RubiesReadAllTransactionsResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesReadAllTransactionsResponse.class);

            RubiesReadAllTransactionsResponse body = response.getBody();
            if (body == null) {
                logger.warn("[Rubies] Read-all wallet transactions returned null body for page={} searchItem={}",
                        req.getPage(), req.getSearchItem());
                return null;
            }

            if (!isRetry && "22".equals(body.getResponseCode())) {
                logger.warn("[Rubies] JWT expired (code 22) on read-all wallet transactions page={} searchItem={}. Attempting token refresh.",
                        req.getPage(), req.getSearchItem());
                if (tryRefreshToken()) {
                    return fetchWalletTransactionsPage(url, req, true);
                }
            }

            if (!body.isSuccess()) {
                logger.warn("[Rubies] Read-all wallet transactions failed page={} searchItem={} code={} msg={}",
                        req.getPage(), req.getSearchItem(), body.getResponseCode(), body.getResponseMessage());
            }
            return body;

        } catch (Exception e) {
            logger.warn("[Rubies] read-all wallet transactions exception page={} searchItem={}: {}",
                    req.getPage(), req.getSearchItem(), e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<String> fetchTransactionStatus(String providerReference) {
        String url = baseUrl + "/" + stage() + "/baas-transaction/tsq";
        RubiesTsqRequest req = new RubiesTsqRequest(providerReference);

        logger.debug("[Rubies] TSQ: ref={}", providerReference);

        try {
            ResponseEntity<RubiesTsqResponse> response =
                    restTemplate.exchange(url, HttpMethod.POST,
                            new HttpEntity<>(req, authHeaders()),
                            RubiesTsqResponse.class);

            RubiesTsqResponse body = response.getBody();

            if (body == null) return Optional.empty();

            String status;
            if (body.isSuccess())  status = "SUCCESS";
            else if (body.isPending()) status = "PENDING";
            else status = "FAILED";

            logger.info("[Rubies] TSQ result: ref={} → {}", providerReference, status);
            return Optional.of(status);

        } catch (Exception e) {
            logger.warn("[Rubies] fetchTransactionStatus exception for ref={}: {}", providerReference, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Webhook helpers ───────────────────────────────────────────────────────

    /**
     * Rubies webhooks have NO {@code event} field — the type must be synthesised
     * from {@code drCr} and {@code responseCode}:
     *
     * <ul>
     *   <li>DR + 00  → TRANSFER.SUCCESS  (outbound transfer confirmed)</li>
     *   <li>DR + !00 → TRANSFER.FAILED   (outbound transfer rejected)</li>
     *   <li>CR + 00  → CREDIT.RECEIVED   (inbound deposit)</li>
     * </ul>
     */
    @Override
    public String extractWebhookEventType(String rawPayload) {
        try {
            RubiesWebhookPayload payload = objectMapper.readValue(rawPayload, RubiesWebhookPayload.class);
            String drCr         = payload.getDrCr()         != null ? payload.getDrCr().toUpperCase()         : "";
            String responseCode = payload.getResponseCode() != null ? payload.getResponseCode()               : "";

            if ("DR".equals(drCr)) {
                return "00".equals(responseCode) ? "TRANSFER.SUCCESS" : "TRANSFER.FAILED";
            }
            if ("CR".equals(drCr) && "00".equals(responseCode)) {
                return "CREDIT.RECEIVED";
            }
            logger.warn("[Rubies] Unknown webhook drCr={} responseCode={} — treating as UNKNOWN", drCr, responseCode);
            return "UNKNOWN";
        } catch (JsonProcessingException e) {
            logger.warn("[Rubies] Cannot parse webhook event type: {}", e.getMessage());
            return "UNKNOWN";
        }
    }

    /**
     * Extracts our payment reference from the Rubies webhook.
     * Rubies echoes back the {@code transactionReference} we sent as {@code paymentReference}.
     * There is NO nested {@code data} object.
     */
    @Override
    public String extractWebhookReference(String rawPayload) {
        try {
            RubiesWebhookPayload payload = objectMapper.readValue(rawPayload, RubiesWebhookPayload.class);
            return payload.getPaymentReference();
        } catch (JsonProcessingException e) {
            logger.warn("[Rubies] Cannot parse webhook reference: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validates the Rubies webhook {@code X-Rubies-Signature} header using HMAC-SHA512.
     *
     * <p>Operates in two modes:
     * <ul>
     *   <li><b>Secret set</b> ({@code rubies.webhook.secret} / {@code RUBIES_WEBHOOK_SECRET}):
     *       computes HMAC-SHA512 of the raw payload and compares against the header value.
     *       Returns {@code false} (rejects) if the header is absent or the digest does not match.</li>
     *   <li><b>No secret</b>: logs a security warning and accepts the request so webhooks
     *       are not silently dropped during development. Tighten before production.</li>
     * </ul>
     */
    @Override
    public boolean validateWebhookSignature(String signature, String rawPayload) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            logger.warn("[Rubies][SECURITY] No webhook secret configured — accepting without verification. " +
                    "Set RUBIES_WEBHOOK_SECRET (rubies.webhook.secret) before production.");
            return true;
        }
        if (signature == null || signature.isBlank()) {
            logger.warn("[Rubies-WEBHOOK] Missing X-Rubies-Signature header — request rejected.");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] hashBytes = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            String expected = hex.toString();
            boolean valid = expected.equalsIgnoreCase(signature.trim());
            if (!valid) {
                logger.warn("[Rubies-WEBHOOK] Signature mismatch — expected={} received={}",
                        expected, signature.trim());
            }
            return valid;
        } catch (Exception e) {
            logger.error("[Rubies-WEBHOOK] HMAC-SHA512 validation threw an exception", e);
            return false;
        }
    }

    // ── PSP methods not applicable to Rubies (BaaS model) ────────────────────

    /**
     * For Rubies the recipient bank details are stored locally on the Wallet entity,
     * not registered at the PSP level — so we simply return true to let WalletService
     * persist the details without making a Rubies API call.
     */
    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName,
                                            String bankCode, String accountNumber) {
        logger.debug("[Rubies] updateWithdrawalBankInfo: storing locally (no PSP call needed)");
        return true;
    }

    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        return Collections.emptyMap();
    }

    @Override
    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
        throw new UnsupportedOperationException(
                "Rubies uses NIP fund-transfer for withdrawals. Use initiateTransferWithContext.");
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String stage() {
        return systemConfig.getString(SystemConfigService.RUBIES_STAGE, "dev");
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", currentApiKey);
        return headers;
    }

    /**
     * Hot-reloads the Rubies API JWT without restarting the server.
     * Called by the admin endpoint {@code POST /admin/rubies/update-api-key}.
     */
    public void updateApiKey(String newKey) {
        currentApiKey = newKey;
        logger.info("[Rubies] API key updated via admin endpoint. No restart needed.");
    }

    /**
     * Attempts to get a fresh JWT from Rubies by re-authenticating with stored credentials.
     * Only called when {@code RUBIES_EMAIL} and {@code RUBIES_PASSWORD} are configured.
     * Returns true if the token was successfully refreshed.
     */
    private boolean tryRefreshToken() {
        if (isBlank(rubiesEmail) || isBlank(rubiesPassword)) {
            logger.error("[Rubies] JWT expired (code 22) and auto-refresh credentials are not configured. " +
                    "Get a new token from the Rubies dashboard and either: " +
                    "(1) update RUBIES_API_KEY on Render and redeploy, or " +
                    "(2) call POST /admin/rubies/update-api-key with the new token (no restart needed). " +
                    "Set RUBIES_EMAIL + RUBIES_PASSWORD env vars to enable automatic renewal.");
            return false;
        }

        // Only one thread refreshes at a time; others wait for it to finish.
        tokenRefreshLock.lock();
        try {
            // Another thread may have already refreshed while we waited for the lock.
            // Re-check by attempting the call would be circular, so we just proceed.
            String loginUrl = !isBlank(rubiesLoginUrl)
                    ? rubiesLoginUrl
                    : baseUrl + "/auth/login";

            logger.info("[Rubies] JWT expired — attempting auto-refresh via {}", loginUrl);

            Map<String, String> loginBody = new HashMap<>();
            loginBody.put("email",    rubiesEmail);
            loginBody.put("password", rubiesPassword);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                    loginUrl, HttpMethod.POST,
                    new HttpEntity<>(loginBody, headers),
                    Map.class
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> body = response.getBody();
            if (body != null) {
                // Rubies may return the token under various field names
                String newToken = firstNonBlank(body,
                        "token", "accessToken", "access_token", "jwt", "data");
                if (newToken != null) {
                    currentApiKey = newToken;
                    logger.info("[Rubies] JWT auto-refreshed successfully.");
                    return true;
                }
            }
            logger.error("[Rubies] Login endpoint returned no recognisable token field. Body: {}", body);
            return false;

        } catch (Exception e) {
            logger.error("[Rubies] Auto-refresh failed: {}", e.getMessage(), e);
            return false;
        } finally {
            tokenRefreshLock.unlock();
        }
    }

    /** Pulls the first non-blank String value from {@code map} by trying each key in order. */
    @SuppressWarnings("unchecked")
    private String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val instanceof String && !((String) val).isBlank()) {
                return (String) val;
            }
            // Handle nested "data" object that itself contains the token
            if (val instanceof Map) {
                String inner = firstNonBlank((Map<String, Object>) val, "token", "accessToken", "access_token", "jwt");
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isIsoDate(String value) {
        return value != null && value.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    private boolean isKycIdentityMismatch(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("name does not match")
                || normalized.contains("first name")
                || normalized.contains("last name")
                || normalized.contains("surname")
                || normalized.contains("dob")
                || normalized.contains("date of birth")
                || normalized.contains("bvn does not match");
    }

    private String normalizePersonName(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String str(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val == null) return defaultValue;
        String s = val.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }
}
