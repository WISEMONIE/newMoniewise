package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.TncRequest;
import com.moniewise.moniewise_backend.dto.response.TncResponse;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/tnc")
public class TncController {

    @Autowired
    private UserService userService;

    @GetMapping
    public ResponseEntity<?> getTnc() {
        String terms = "Moniewise helps you budget with discipline. Charges: ₦200 per 30 days, ₦15 processing fee on withdrawals, 5% on Emergency withdrawals, 2% on transfers (Safe Lock transfers free). Withdrawal fees are shown before confirmation and deducted with the withdrawal amount while the recipient receives the amount you selected. We protect your data and don't share bank details.";
        TncResponse tnc = new TncResponse(terms, "1.0");
        return ResponseEntity.ok(tnc);
    }

    @PatchMapping
    public ResponseEntity<?> acceptTnc(Principal principal, @RequestBody TncRequest request) {
        String email = principal.getName();
        userService.acceptTnc(email, request.isAccepted());
        String message = request.isAccepted() ? "TnC accepted" : "TnC declined";
        return ResponseEntity.ok(Map.of("message", message));
    }
}
