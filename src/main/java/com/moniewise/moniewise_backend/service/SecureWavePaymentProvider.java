package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.response.BvnVerificationResultDto;
import com.moniewise.moniewise_backend.exception.BvnProviderUnavailableException;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.util.*;

@Service
@Primary // 👈 This tells Spring to use SecureWave instead of any other provider
@Slf4j
@RequiredArgsConstructor
public class SecureWavePaymentProvider implements PaymentProvider {

    private final RestTemplate restTemplate;

    @Value("${securewave.base-url}")
    private String baseUrl;

    @Value("${securewave.secret-key}")
    private String secretKey;

    @Value("${securewave.public-key}")
    private String publicKey;

    @Value("${securewave.business-id}")
    private String businessId;

    // --- Helper: SecureWave Headers ---
    private HttpHeaders getSecureWaveHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        headers.set("Authorization", "Bearer " + secretKey); // Ensure "Bearer " has a space!
        headers.set("x-api-key", publicKey);
        return headers;
    }

    private HttpHeaders getSecureWaveFormHeaders() {
        HttpHeaders headers = getSecureWaveHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return headers;
    }

    // ==========================================================
    // 0. BVN VERIFICATION (Identity / KYC Step)
    // POST /api/verify-bvn
    // ==========================================================

    /**
     * Calls the SecureWave BVN verification endpoint and returns a structured
     * DTO built from the response.  The base64 image in personal_info is
     * intentionally dropped — it is not stored or returned to clients.
     *
     * @param email the user's registered email address
     * @param phone the user's registered phone number
     * @param bvn   the 11-digit BVN to verify
     * @return a populated {@link BvnVerificationResultDto}
     * @throws RuntimeException if SecureWave rejects the request or returns
     *                          a failure status
     */
    public BvnVerificationResultDto verifyBvn(String email, String phone, String bvn) {
        String url = baseUrl + "/verify-bvn";

        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("phone", phone);
        payload.put("bvn", bvn);

        log.info("[BVN] Sending verification request to SecureWave for bvn={}***", bvn.substring(0, 4));

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Boolean status = (Boolean) body.get("status");

                if (Boolean.FALSE.equals(status)) {
                    String detail = describe(body);
                    log.error("[BVN] SecureWave returned status=false: {}", detail);
                    throw failure(detail, null);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                if (data == null) {
                    throw new BvnProviderUnavailableException("empty data in response");
                }

                log.info("[BVN] Verification successful for bvn={}***", bvn.substring(0, 4));
                return mapToBvnResult(data);
            }

            throw new BvnProviderUnavailableException("unexpected HTTP status " + response.getStatusCode());

        } catch (HttpClientErrorException e) {
            log.error("[BVN] SecureWave rejected request — HTTP {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
            String detail = describe(e.getResponseBodyAsString());
            int code = e.getRawStatusCode();
            if (code == 401 || code == 403) {
                // Our credentials, not the customer's BVN.
                throw new BvnProviderUnavailableException("HTTP " + code + ": " + detail, e);
            }
            throw failure(detail, e);
        } catch (RuntimeException e) {
            throw e; // already wrapped, re-throw as-is
        } catch (Exception e) {
            log.error("[BVN] Unexpected error during BVN verification: {}", e.getMessage(), e);
            throw new RuntimeException("BVN verification failed: " + e.getMessage());
        }
    }

    /** Shown when SecureWave clearly says the BVN does not exist or is invalid. */
    public static final String BVN_NOT_FOUND_MESSAGE =
            "We could not find a BVN matching those 11 digits. Check the number and try again.";

    /**
     * A clear "this BVN is wrong" is the customer's to fix. Anything else
     * (SecureWave has answered "Data Fetched Successfully" with status
     * "failed") says nothing about the BVN, so it counts as SecureWave
     * failing and the Rubies fallback can take over.
     */
    static RuntimeException failure(String detail, Throwable cause) {
        if (clearlyRejectsBvn(detail)) {
            return new IllegalArgumentException(BVN_NOT_FOUND_MESSAGE, cause);
        }
        return cause == null
                ? new BvnProviderUnavailableException(detail)
                : new BvnProviderUnavailableException(detail, cause);
    }

    static boolean clearlyRejectsBvn(String detail) {
        if (detail == null) {
            return false;
        }
        String text = detail.toLowerCase(java.util.Locale.ROOT);
        return text.contains("invalid bvn")
                || text.contains("bvn is invalid")
                || text.contains("invalid bank verification")
                || text.contains("bvn not found")
                || text.contains("not found")
                || text.contains("does not exist")
                || text.contains("no record")
                || text.contains("could not find")
                || text.contains("bvn is not valid")
                || text.contains("incorrect bvn");
    }

    /** SecureWave's own words (message, data.message, data.status) for logs and classification. */
    @SuppressWarnings("unchecked")
    static String describe(Object responseBody) {
        Map<String, Object> body = null;
        try {
            if (responseBody instanceof Map) {
                body = (Map<String, Object>) responseBody;
            } else if (responseBody != null && !responseBody.toString().isBlank()) {
                body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(responseBody.toString(), Map.class);
            }
        } catch (Exception notJson) {
            return String.valueOf(responseBody);
        }
        if (body == null) {
            return "no response body";
        }
        StringBuilder out = new StringBuilder(String.valueOf(body.getOrDefault("message", "")));
        Object data = body.get("data");
        if (data instanceof Map) {
            Map<String, Object> d = (Map<String, Object>) data;
            if (d.get("message") != null && !d.get("message").equals(body.get("message"))) {
                out.append(" / ").append(d.get("message"));
            }
            if (d.get("status") != null) {
                out.append(" (status ").append(d.get("status")).append(')');
            }
        }
        return out.toString().trim();
    }

    /** Maps the SecureWave {@code data} block to our DTO (image field excluded). */
    @SuppressWarnings("unchecked")
    private BvnVerificationResultDto mapToBvnResult(Map<String, Object> data) {
        BvnVerificationResultDto dto = new BvnVerificationResultDto();

        dto.setBvnNumber(str(data, "bvn_number"));
        dto.setNameOnCard(str(data, "name_on_card"));
        dto.setEnrolmentBank(str(data, "enrolment_bank"));
        dto.setEnrolmentBranch(str(data, "enrolment_branch"));
        dto.setFormattedRegistrationDate(str(data, "formatted_registration_date"));
        dto.setLevelOfAccount(str(data, "level_of_account"));
        dto.setNin(str(data, "nin"));
        dto.setWatchlisted(str(data, "watchlisted"));
        dto.setVerificationStatus(str(data, "verification_status"));

        Object personalInfoObj = data.get("personal_info");
        if (personalInfoObj instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) personalInfoObj;
            dto.setFirstName(str(p, "first_name"));
            dto.setMiddleName(str(p, "middle_name"));
            dto.setLastName(str(p, "last_name"));
            dto.setFullName(str(p, "full_name"));
            dto.setGender(str(p, "gender"));
            dto.setDateOfBirth(str(p, "date_of_birth"));
            dto.setStateOfOrigin(str(p, "state_of_origin"));
            dto.setLgaOfOrigin(str(p, "lga_of_origin"));
            dto.setNationality(str(p, "nationality"));
            dto.setMaritalStatus(str(p, "marital_status"));
            // "image" intentionally omitted
        }

        Object residentialInfoObj = data.get("residential_info");
        if (residentialInfoObj instanceof Map) {
            Map<String, Object> r = (Map<String, Object>) residentialInfoObj;
            dto.setStateOfResidence(str(r, "state_of_residence"));
            dto.setLgaOfResidence(str(r, "lga_of_residence"));
            dto.setResidentialAddress(str(r, "residential_address"));
        }

        return dto;
    }

    /** Safely extracts a String value from a Map without NPE. */
    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return (val == null || "null".equals(val)) ? null : String.valueOf(val);
    }

    // ==========================================================
    // 1. GENERATE VIRTUAL ACCOUNT (The Onboarding Step)
    // ==========================================================
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        String url = baseUrl + "/virtual_accounts/generate";

        // 1. Safely extract data from the User entity
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : new HashMap<>();
        String firstName = profile.getOrDefault("firstName", "Wisemonie").toString();
        String lastName = profile.getOrDefault("lastName", "User").toString();
        
        // Ensure BVN exists before calling!
        if (user.getBvn() == null || user.getBvn().isEmpty()) {
            log.error("Cannot generate SecureWave account: Missing BVN for user {}", user.getEmail());
            throw new IllegalStateException("BVN is required to generate a virtual account.");
        }

        // 2. Build the exact payload SecureWave requested
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", user.getEmail());
        payload.put("first_name", firstName);
        payload.put("last_name", lastName);
        payload.put("phone_number", user.getPhone());
        payload.put("bank_code", Arrays.asList(1, 2, 3)); // As per their docs
        payload.put("business_id", businessId);
        payload.put("account_type", "static");
        payload.put("id_type", "bvn");
        payload.put("id_number", user.getBvn());

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK || response.getStatusCode() == HttpStatus.CREATED) {
                Map<String, Object> body = response.getBody();

                Object dataObj = body.get("data");
                Map<String, Object> accountData = new HashMap<>();

                // Handle the Array that SecureWave sends back
                if (dataObj instanceof List) {
                    List<?> dataList = (List<?>) dataObj;
                    if (!dataList.isEmpty()) {
                        accountData = (Map<String, Object>) dataList.get(0);
                    }
                } else if (dataObj instanceof Map) {
                    accountData = (Map<String, Object>) dataObj;
                }

                // 🚨 Use the exact keys from the SecureWave docs!
                Object accNumObj = accountData.get("account_number");
                Object bankObj = accountData.get("account_bank"); // <--- THE MISSING LINK!

                if (accNumObj == null || bankObj == null) {
                    log.error("SecureWave Response missing keys: {}", accountData);
                    throw new RuntimeException("SecureWave did not return valid account details.");
                }

                Map<String, String> result = new HashMap<>();
                result.put("accountNumber", String.valueOf(accNumObj));
                result.put("bank", String.valueOf(bankObj));

                return result;
            }
        } catch (Exception e) {
            log.error("SecureWave Virtual Account Generation Failed: {}", e.getMessage());
            throw new RuntimeException("Failed to generate virtual account");
        }
        return new HashMap<>();
    }

    // ==========================================================
    // 2. RESOLVE ACCOUNT (The KYC Verification Step - X-RAY EDITION)
    // ==========================================================
    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
        String url = baseUrl + "/customer_withdrawals/validate-account-name";

        try {
            // 1. Build the exact JSON String
            ObjectMapper mapper = new ObjectMapper();
            Map<String, String> payloadMap = new HashMap<>();
            payloadMap.put("bank_code", bankCode);
            payloadMap.put("account_number", accountNumber);
            String jsonBody = mapper.writeValueAsString(payloadMap);

            // 2. Get the headers
            HttpHeaders headers = getSecureWaveHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 🕵️‍♂️ THE X-RAY: Print EXACTLY what we are sending
            // We mask the middle of the keys so you don't leak them in logs, but we show the start/end to catch spaces!
            String safeSecret = secretKey != null && secretKey.length() > 8
                    ? secretKey.substring(0, 6) + "..." + secretKey.substring(secretKey.length() - 2) : "INVALID_KEY";
            String authHeaderValue = headers.getFirst("Authorization");

            log.info("================ SECUREWAVE OUTGOING X-RAY ================");
            log.info("URL:         --> '{}'", url);
            log.info("Raw Secret:  --> '{}'", safeSecret);
            log.info("Auth Header: --> '{}'", authHeaderValue);
            log.info("Payload:     --> {}", jsonBody);
            log.info("===========================================================");

            // 3. Send the pristine JSON String
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(jsonBody, headers),
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Boolean status = (Boolean) body.get("status");

                if (status != null && status) {
                    Map<String, Object> data = (Map<String, Object>) body.get("data");
                    if (data != null && data.containsKey("account_name")) {
                        return (String) data.get("account_name");
                    }
                } else {
                    log.error("SecureWave returned false status: {}", body.get("message"));
                }
            }
            throw new RuntimeException("Could not verify account name. Please check the details.");

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 🚨 CAPTURE THE EXACT ERROR FROM THE PROVIDER
            log.error("❌ SecureWave REJECTED the request.");
            log.error("HTTP Status: {}", e.getStatusCode());
            log.error("Response Body: {}", e.getResponseBodyAsString());

            // Pass the actual message back up so you can see it in Postman
            throw new RuntimeException("SecureWave API Error: " + e.getResponseBodyAsString());

        } catch (Exception e) {
            log.error("SecureWave Account Resolution Failed: {}", e.getMessage(), e);
            throw new RuntimeException("An internal error occurred during verification.");
        }
    }

    // ==========================================================
    // 3. WITHDRAWAL (The Closed-Loop Payout Step)
    // ==========================================================
    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String reference, String narration) {
        // Replace with SecureWave's actual disbursement endpoint
        String url = baseUrl + "/transfers/initiate";

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("reference", reference);
        payload.put("narration", narration);
        payload.put("bank_code", bankCode);
        payload.put("account_number", accountNumber);
        payload.put("currency", "NGN");

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK) {
                Map<String, Object> body = response.getBody();
                Map<String, Object> data = (Map<String, Object>) body.get("data");

                return data.get("transaction_reference").toString();
            }
        } catch (Exception e) {
            log.error("SecureWave Transfer Failed: {}", e.getMessage());
            throw new RuntimeException("Transfer Failed: " + e.getMessage());
        }
        throw new RuntimeException("Transfer Failed");
    }

    // Add this near the top of your class to hold the cached banks
    private List<Map<String, Object>> cachedBanks = null;

    // ==========================================================
    // 5. GET BANKS
    // ==========================================================
    // @Cacheable REMOVED: Spring's @Cacheable stores the return value unconditionally,
    // including empty lists returned on upstream failure. A single 503 from SecureWave
    // would cache [] for up to 12 hours (the CacheConfig evict interval), making the
    // entire bank list completely unavailable until the next server restart or evict.
    // Stale-on-error fallback is now handled in WalletService._lastKnownBanks —
    // it only updates on non-empty results, so a failure always serves real data.
    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        // Using the base URL from your environment variables
        String url = baseUrl + "/banks";

        try {
            // We expect a Map representing the outer JSON object { "status": true, "data": [...] }
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(getSecureWaveHeaders()),
                    Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();

                // 1. Check if the API says it was successful
                Boolean status = (Boolean) body.get("status");
                if (status != null && status) {

                    // 2. Safely extract the "data" array
                    Object dataObj = body.get("data");
                    if (dataObj instanceof List) {
                        return (List<Map<String, Object>>) dataObj;
                    }
                } else {
                    System.out.println("SecureWave returned failure status: {} " +  body.get("message"));
                }
            }
        } catch (Exception e) {
            System.out.println("SecureWave API Exception while fetching banks: {} " + e.getMessage());
        }

        // If anything fails, return an empty list.
        // Your controller will catch this and gracefully tell the user "Bank list is currently unavailable"
        return new ArrayList<>();
    }
    // ==========================================================
    // 6. UPDATE WITHDRAWAL BANK INFO
    // ==========================================================
    @Override
    public boolean updateWithdrawalBankInfo(String email, String bankName, String accountName, String bankCode, String accountNumber) {
        String url = baseUrl + "/customer_withdrawals/bank-info";

        Map<String, Object> payload = new HashMap<>();
        payload.put("customer_email", email);
        payload.put("bank_name", bankName);
        payload.put("account_name", accountName);
        payload.put("bank_code", bankCode);
        payload.put("account_number", accountNumber);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, getSecureWaveHeaders()), Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // Return true if status is true in their JSON response
                Boolean status = (Boolean) response.getBody().get("status");
                return status != null && status;
            }
        } catch (Exception e) {
            log.error("SecureWave Update Bank Info Failed: {}", e.getMessage());
        }

        throw new RuntimeException("Failed to update bank details with the payment provider.");
    }


    @Override
    public Map<String, Object> getWithdrawalBankInfo(String email) {
        String url = baseUrl + "/customer_withdrawals/bank-info";
        String uri = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("customer_email", email)
                .toUriString();

        try {
            HttpEntity<Void> requestEntity = new HttpEntity<>(getSecureWaveHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                Boolean status = (Boolean) body.get("status");
                Object dataObj = body.get("data");
                if (status != null && status && dataObj instanceof Map) {
                    return new HashMap<>((Map<String, Object>) dataObj);
                }
            }
        } catch (Exception e) {
            log.error("SecureWave Fetch Bank Info Failed: {}", e.getMessage());
        }

        return Map.of();
    }
    // ==========================================================
    // 7. INITIATE WITHDRAWAL (Closed-Loop)
    // ==========================================================
//    @Override
//    public String initiateWithdrawal(String email, BigDecimal amount, String narration) {
//        String url = baseUrl + "/customer_withdrawals/withdraw";
//
////        MultiValueMap<String, String> payload = new LinkedMultiValueMap<>();
////        payload.add("customer_email", email);
////        payload.add("amount", amount.toPlainString());
////        payload.add("narration", narration);
//
//        Map<String, Object> payload = new HashMap<>();
//        payload.put("customer_email", email);
//        payload.put("amount", amount);
//        payload.put("narration", narration);
//
//        try {
//            ResponseEntity<Map> response = restTemplate.postForEntity(
//                    url,
//                    new HttpEntity<>(payload, getSecureWaveFormHeaders()),
//                    Map.class);
//
//            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
//                Map<String, Object> body = response.getBody();
//
//                Boolean outerStatus = (Boolean) body.get("status");
//                if (outerStatus != null && outerStatus) {
//
//                    // Navigate through the double "data" nesting
//                    Map<String, Object> outerData = (Map<String, Object>) body.get("data");
//                    if (outerData != null) {
//                        Map<String, Object> innerData = (Map<String, Object>) outerData.get("data");
//
//                        if (innerData != null && innerData.containsKey("reference")) {
//                            return innerData.get("reference").toString(); // The SecureWave Reference!
//                        }
//                    }
//                } else {
//                    log.error("SecureWave Withdrawal Failed: {}", body.get("message"));
//                    throw new RuntimeException(body.get("message").toString());
//                }
//            }
//        } catch (Exception e) {
//            log.error("SecureWave Withdrawal API Exception: {}", e.getMessage());
//            throw new RuntimeException("Failed to process withdrawal with the payment provider.");
//        }
//
//        throw new RuntimeException("Withdrawal processing failed.");
//    }

    @Override
    public String initiateWithdrawal(String customerEmail, BigDecimal amount, String narration) {
        String url = baseUrl + "/customer_withdrawals/withdraw";

        Map<String, Object> payload = new HashMap<>();
        payload.put("customer_email", customerEmail);
        payload.put("amount", amount);
        payload.put("narration", narration);

        HttpHeaders headers = getSecureWaveHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("SecureWave withdrawal failed with status: " + response.getStatusCode());
            }

            Map<String, Object> body = response.getBody();

            Boolean outerStatus = (Boolean) body.get("status");
            if (Boolean.FALSE.equals(outerStatus)) {
                throw new RuntimeException(String.valueOf(body.getOrDefault("message", "Withdrawal failed")));
            }

            Object dataObj = body.get("data");

            if (dataObj instanceof Map) {
                Map<String, Object> outerData = (Map<String, Object>) dataObj;

                Object innerDataObj = outerData.get("data");
                if (innerDataObj instanceof Map) {
                    Map<String, Object> innerData = (Map<String, Object>) innerDataObj;
                    Object reference = innerData.get("reference");
                    if (reference != null) {
                        return reference.toString();
                    }
                }

                Object reference = outerData.get("reference");
                if (reference != null) {
                    return reference.toString();
                }
            }

            Object reference = body.get("reference");
            if (reference != null) {
                return reference.toString();
            }

            throw new RuntimeException("SecureWave withdrawal response did not contain reference: " + body);

        } catch (HttpClientErrorException e) {
            log.error("SecureWave Withdrawal HTTP Error: status={}, body={}",
                    e.getStatusCode(),
                    e.getResponseBodyAsString()
            );
            throw new RuntimeException("Failed to process withdrawal with the payment provider.");
        } catch (Exception e) {
            log.error("SecureWave Withdrawal API Exception: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process withdrawal with the payment provider.");
        }
    }
}

