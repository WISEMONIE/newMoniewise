package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.CreateFromTemplateRequest;
import com.moniewise.moniewise_backend.dto.request.SaveTemplateRequest;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.BudgetTemplateResponse;
import com.moniewise.moniewise_backend.exception.InsufficientFundsException;
import com.moniewise.moniewise_backend.service.BudgetTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/budget-templates")
public class BudgetTemplateController {

    private static final Logger logger = LoggerFactory.getLogger(BudgetTemplateController.class);

    @Autowired
    private BudgetTemplateService templateService;

    @PostMapping
    public ResponseEntity<?> saveTemplate(@RequestBody SaveTemplateRequest request, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetTemplateResponse response = templateService.saveTemplate(request, email);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error saving template", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to save template"));
        }
    }

    @GetMapping
    public ResponseEntity<?> getTemplates(Authentication authentication) {
        try {
            String email = authentication.getName();
            List<BudgetTemplateResponse> templates = templateService.getTemplates(email);
            return ResponseEntity.ok(templates);
        } catch (Exception e) {
            logger.error("Error fetching templates", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch templates"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getTemplate(@PathVariable Long id, Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetTemplateResponse template = templateService.getTemplate(id, email);
            return ResponseEntity.ok(template);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error fetching template {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fetch template"));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateTemplate(@PathVariable Long id, @RequestBody SaveTemplateRequest request,
                                            Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetTemplateResponse response = templateService.updateTemplate(id, request, email);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating template {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update template"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteTemplate(@PathVariable Long id, Authentication authentication) {
        try {
            String email = authentication.getName();
            templateService.deleteTemplate(id, email);
            return ResponseEntity.ok(Map.of("message", "Template deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting template {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete template"));
        }
    }

    @PostMapping("/{id}/create-budget")
    public ResponseEntity<?> createBudgetFromTemplate(@PathVariable Long id,
                                                       @RequestBody CreateFromTemplateRequest request,
                                                       Authentication authentication) {
        try {
            String email = authentication.getName();
            BudgetResponse response = templateService.createBudgetFromTemplate(id, request, email);
            return new ResponseEntity<>(response, HttpStatus.CREATED);
        } catch (InsufficientFundsException e) {
            Map<String, Object> body = insufficientBudgetCreationFundsBody(e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating budget from template {}", id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create budget from template"));
        }
    }

    private Map<String, Object> insufficientBudgetCreationFundsBody(InsufficientFundsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Insufficient Funds");
        body.put("code", "INSUFFICIENT_BUDGET_CREATION_FUNDS");
        body.put("message", e.getMessage());
        body.putAll(e.getDetails());
        return body;
    }
}
