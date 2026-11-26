//package com.moniewise.moniewise_backend.thirdParty;
//
//import com.moniewise.moniewise_backend.entity.User;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.context.annotation.Profile;
//import org.springframework.stereotype.Service;
//
//import java.util.Map;
//import java.util.concurrent.ThreadLocalRandom;
//
//@Service
////@Profile({"dev", "test"}) // active only in dev & test
//@Profile("stub")
//public class PaystackGatewayStub implements PaymentGateway {
//
//    private static final Logger logger = LoggerFactory.getLogger(PaystackGatewayStub.class);
//
//    private static final String[] TEST_BANKS = {
//            "MonieWise Test Bank",
//            "Virtual Titan Bank",
//            "Stub Access Bank",
//            "Stub Paystack Bank"
//    };
//
//    @Override
//    public Map<String, String> createVirtualAccount(User user) {
//        logger.info("Simulating Paystack virtual account creation for user {}", user.getId());
//
//        String testAccountNumber = generateTestAccountNumber(user.getId());
//        String bank = TEST_BANKS[ThreadLocalRandom.current().nextInt(TEST_BANKS.length)];
//
//        return Map.of(
//                "accountNumber", testAccountNumber,
//                "bank", bank
//        );
//    }
//
//    private String generateTestAccountNumber(Long userId) {
//        return String.format("STUB-%d-%05d",
//                userId,
//                ThreadLocalRandom.current().nextInt(10000, 99999)
//        );
//    }
//}
