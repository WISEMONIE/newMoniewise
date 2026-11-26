//package com.moniewise.moniewise_backend.thirdParty;
//
//import com.moniewise.moniewise_backend.entity.User;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Profile;
//import org.springframework.http.*;
//import org.springframework.stereotype.Component;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Component
//@Profile("!stub")
//public class PaystackGateway implements PaymentGateway {
//
//    private static final Logger logger = LoggerFactory.getLogger(PaystackGateway.class);
//
//    @Value("${paystack.secret.key}")
//    private String secretKey;
//
//    @Value("${paystack.base.url}")
//    private String baseUrl;
//
//    private final RestTemplate restTemplate = new RestTemplate();
//
//    @Override
//    public Map<String, String> createVirtualAccount(User user) {
//        String url = baseUrl + "/dedicated_account";
//
//        // Request body as required by Paystack
//        Map<String, Object> requestBody = new HashMap<>();
//        requestBody.put("customer", user.getEmail()); // usually you must create customer first
//        requestBody.put("preferred_bank", "wema-bank"); // optional
//        requestBody.put("name", user.getName());
//        requestBody.put("email", user.getEmail());
//        requestBody.put("phone", user.getPhone());
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//        headers.setBearerAuth(secretKey);
//
//        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
//
//        try {
//            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
//
//            logger.info("Paystack response: {}", response.getBody());
//
//            Map<String, String> result = new HashMap<>();
//            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
//                Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
//                result.put("accountNumber", (String) data.get("account_number"));
//                result.put("bank", (String) data.get("bank_name"));
//            }
//            return result;
//
//        } catch (Exception e) {
//            logger.error("Error creating Paystack account", e);
//            return Map.of("error", "Failed to create virtual account");
//        }
//    }
//}
