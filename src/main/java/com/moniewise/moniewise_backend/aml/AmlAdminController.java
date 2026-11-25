package com.moniewise.moniewise_backend.aml;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Review tools for large-inflow holds and wallet freezes.
 *
 * <ul>
 *   <li>{@code GET  /admin/aml/todos} the dashboard to-do lists</li>
 *   <li>{@code POST /admin/aml/holds/{id}/rubies-informed} {"note"}</li>
 *   <li>{@code POST /admin/aml/wallets/{userId}/retry} open a failed wallet now</li>
 *   <li>{@code GET  /admin/aml/holds?view=open|closed} open holds oldest first, or recent releases</li>
 *   <li>{@code GET  /admin/aml/holds/{id}} the case file, with the Rubies escalation text</li>
 *   <li>{@code POST /admin/aml/holds/{id}/contact} {"note"} the customer got in touch</li>
 *   <li>{@code POST /admin/aml/holds/{id}/note} {"note"}</li>
 *   <li>{@code POST /admin/aml/holds/{id}/release} {"reason"}</li>
 *   <li>{@code POST /admin/aml/holds/{id}/escalate} {"note"}</li>
 *   <li>{@code POST /admin/aml/users/{userId}/freeze} {"reason"}</li>
 *   <li>{@code POST /admin/aml/users/{userId}/unfreeze} {"reason"}</li>
 * </ul>
 */
@RestController
@RequestMapping("/admin/aml")
@PreAuthorize("hasRole('ADMIN')")
public class AmlAdminController {

    private final AmlCaseService cases;

    public AmlAdminController(AmlCaseService cases) {
        this.cases = cases;
    }

    /** {@code ?view=open} (default) or {@code ?view=closed}. */
    @GetMapping("/holds")
    public List<Map<String, Object>> holds(@RequestParam(defaultValue = "open") String view) {
        return cases.holdSummaries(!"closed".equalsIgnoreCase(view));
    }

    /** The dashboard: to-dos split into inform Rubies, review now, waiting, wallets to fix and frozen. */
    @GetMapping("/todos")
    public Map<String, Object> todos() {
        return cases.todos();
    }

    @PostMapping("/holds/{id}/rubies-informed")
    public InflowHold rubiesInformed(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body,
                                     Authentication auth) {
        return cases.markRubiesInformed(id, auth.getName(), body == null ? null : body.get("note"));
    }

    @PostMapping("/wallets/{userId}/retry")
    public Map<String, Object> retryWallet(@PathVariable Long userId, Authentication auth) {
        return cases.retryWalletOpening(userId, auth.getName());
    }

    @GetMapping("/holds/{id}")
    public Map<String, Object> caseFile(@PathVariable Long id) {
        return cases.caseFile(id);
    }

    @PostMapping("/holds/{id}/contact")
    public InflowHold contact(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        return cases.recordContact(id, auth.getName(), body.get("note"));
    }

    @PostMapping("/holds/{id}/note")
    public InflowHold note(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        return cases.addNote(id, auth.getName(), body.get("note"));
    }

    @PostMapping("/holds/{id}/release")
    public InflowHold release(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        return cases.release(id, auth.getName(), body.get("reason"));
    }

    @PostMapping("/holds/{id}/escalate")
    public Map<String, Object> escalate(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> body,
                                        Authentication auth) {
        return cases.escalate(id, auth.getName(), body == null ? null : body.get("note"));
    }

    @PostMapping("/users/{userId}/freeze")
    public ResponseEntity<Map<String, Object>> freeze(@PathVariable Long userId,
                                                      @RequestBody Map<String, String> body,
                                                      Authentication auth) {
        cases.freeze(userId, auth.getName(), body.get("reason"));
        return ResponseEntity.ok(Map.of("userId", userId, "wallet", "FROZEN"));
    }

    @PostMapping("/users/{userId}/unfreeze")
    public ResponseEntity<Map<String, Object>> unfreeze(@PathVariable Long userId,
                                                        @RequestBody Map<String, String> body,
                                                        Authentication auth) {
        cases.unfreeze(userId, auth.getName(), body.get("reason"));
        return ResponseEntity.ok(Map.of("userId", userId, "wallet", "ACTIVE"));
    }
}
