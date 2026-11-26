package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.MonnifyLoginResponse;
import com.moniewise.moniewise_backend.dto.response.MonnifyTransactionResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.externalTransfers.PaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public abstract class MonnifyPaymentProvider implements PaymentProvider {

    private final RestTemplate restTemplate;

//    @Value("${monnify.base-url}")
    private String baseUrl;

//    @Value("${monnify.api-key}")
    private String apiKey;

//    @Value("${monnify.secret-key}")
    private String secretKey;

//    @Value("${monnify.contract-code}")
    private String contractCode;

//    @Value("${monnify.wallet-account-number}")
    private String sourceAccountNumber;

    // 1. LOGIN (Get the Token)
    private String getAccessToken() {
        String authString = apiKey + ":" + secretKey;
        String base64Auth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + base64Auth);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<MonnifyLoginResponse> response = restTemplate.postForEntity(
                    baseUrl + "/api/v1/auth/login", new HttpEntity<>(headers), MonnifyLoginResponse.class);

            if (response.getBody() != null && response.getBody().isRequestSuccessful()) {
                return response.getBody().getResponseBody().getAccessToken();
            }
        } catch (Exception e) {
            log.error("Monnify Login Failed: {}", e.getMessage());
        }
        throw new RuntimeException("Authentication failed");
    }

    // 2. CREATE RESERVED ACCOUNT (For User Wallet)
    public Map<String, String> createReservedAccount(String email, String name) {
        String token = getAccessToken();
        String url = baseUrl + "/api/v2/bank-transfer/reserved-accounts";

        Map<String, Object> payload = new HashMap<>();
        payload.put("accountReference", "MW-" + System.currentTimeMillis()); // Unique Ref
        payload.put("accountName", name);
        payload.put("currencyCode", "NGN");
        payload.put("contractCode", contractCode);
        payload.put("customerEmail", email);
        payload.put("customerName", name);
        payload.put("getAllAvailableBanks", true); // Gets Wema, Moniepoint, etc.

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            // We use generic Map here for simplicity, but you can create a DTO for this response too
            ResponseEntity<Map> response = restTemplate.postForEntity(url, new HttpEntity<>(payload, headers), Map.class);
            
            if (response.getBody() != null && (Boolean) response.getBody().get("requestSuccessful")) {
                Map body = (Map) response.getBody().get("responseBody");
                // Extract accounts list
                java.util.List accounts = (java.util.List) body.get("accounts");
                if (!accounts.isEmpty()) {
                    Map firstAccount = (Map) accounts.get(0);
                    Map<String, String> result = new HashMap<>();
                    result.put("bank", (String) firstAccount.get("bankName"));;
                    result.put("accountNumber", (String) firstAccount.get("accountNumber"));
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("Create Account Failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // 👇 ADD THIS NEW METHOD TO SATISFY THE PaymentGateway INTERFACE 👇
    @Override
    public Map<String, String> createVirtualAccount(User user) {
        // Reuse the Monnify logic we wrote earlier
        // We extract the name and email from the User entity
        String name = getSafeName(user);
        return createReservedAccount(user.getEmail(), name);
    }

    @Override
    public List<Map<String, Object>> getSupportedBanks() {
        return null;
    }

    // Helper to avoid null names
    private String getSafeName(User user) {
        if (user.getProfileData() != null && user.getProfileData().containsKey("name")) {
            return user.getProfileData().get("name").toString();
        }
        return "Wisemonie User";
    }

    // 3. SEND MONEY (Transfers)
    @Override
    public String initiateTransfer(String bankCode, String accountNumber, String accountName, BigDecimal amount, String reference, String narration) {
        String token = getAccessToken();
        String url = baseUrl + "/api/v2/disbursements/single";

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("reference", reference);
        payload.put("narration", narration);
        payload.put("destinationBankCode", bankCode);
        payload.put("destinationAccountNumber", accountNumber);
        payload.put("currency", "NGN");
        payload.put("sourceAccountNumber", sourceAccountNumber);
        payload.put("walletId", sourceAccountNumber); // Sometimes required

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<MonnifyTransactionResponse> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), MonnifyTransactionResponse.class);

            if (response.getBody() != null && response.getBody().isRequestSuccessful()) {
                return response.getBody().getResponseBody().getTransactionReference();
            }
        } catch (Exception e) {
            log.error("Transfer Failed: {}", e.getMessage());
            throw new RuntimeException("Transfer Failed: " + e.getMessage());
        }
        throw new RuntimeException("Transfer Failed");
    }

    @Override
    public String resolveAccount(String bankCode, String accountNumber) {
       // ... (Use the logic from my previous message for this part)
       return "Mock User"; // Placeholder if you haven't implemented it yet
    }

    public Map<String, String> initializeCardPayment(User user, BigDecimal amount) {
        String token = getAccessToken();
        String url = baseUrl + "/api/v1/merchant/transactions/init-transaction";

        String paymentRef = "MW-CARD-" + System.currentTimeMillis() + "-" + user.getId();

        String name = user.getProfileData().get("name").toString();

        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", amount);
        payload.put("customerName", name);
        payload.put("customerEmail", user.getEmail());
        payload.put("paymentReference", paymentRef);
        payload.put("paymentDescription", "Wallet Top-up");
        payload.put("currencyCode", "NGN");
        payload.put("contractCode", contractCode);
        // Replace with your actual frontend URL where user goes after payment
        payload.put("redirectUrl", "http://192.168.4.182:9000/wallet");
        payload.put("paymentMethods", java.util.Arrays.asList("CARD", "ACCOUNT_TRANSFER"));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    url, new HttpEntity<>(payload, headers), Map.class);

            if (response.getBody() != null && (Boolean) response.getBody().get("requestSuccessful")) {
                Map body = (Map) response.getBody().get("responseBody");

                Map<String, String> result = new HashMap<>();
                result.put("checkoutUrl", (String) body.get("checkoutUrl"));
                result.put("paymentReference", (String) body.get("paymentReference"));
                return result;
            }
        } catch (Exception e) {
            log.error("Card Init Failed: {}", e.getMessage());
        }
        throw new RuntimeException("Failed to initialize card payment");
    }
}