package com.moniewise.moniewise_backend.utils; //

import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;

@Component
public class MonnifyUtils {

    private static final String HMAC_SHA512 = "HmacSHA512"; //

    public String computeHMAC512TransactionHash(String data, String merchantClientSecret) { //
        try {
            SecretKeySpec secretKeySpec = new SecretKeySpec(merchantClientSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA512);
            Mac mac = Mac.getInstance(HMAC_SHA512);
            mac.init(secretKeySpec);

            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return toHexString(rawHmac);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate Monnify Hash", e);
        }
    }

    private String toHexString(byte[] bytes) { //
        try (Formatter formatter = new Formatter()) {
            for (byte b : bytes) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        }
    }
}