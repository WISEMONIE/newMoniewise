package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.LegalDocumentResponse;
import com.moniewise.moniewise_backend.enums.LegalDocumentType;
import com.moniewise.moniewise_backend.service.LegalDocumentService;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;

/**
 * PUBLIC, unauthenticated, browser-readable legal pages.
 *
 * Google Play (and app-review teams generally) require the privacy policy —
 * and, for apps with account creation, an account-deletion page — to be
 * reachable by anyone with the URL, rendered as a readable web page, with no
 * login. The JSON endpoints under /legal/** serve the in-app viewer and stay
 * authenticated; these routes render the SAME live documents (DB-backed,
 * admin-versioned via LegalDocumentService) as standalone HTML.
 *
 *   GET /privacy-policy   GET /terms-of-use   GET /delete-account
 *
 * All three are permitAll'd in SecurityConfig.
 */
@RestController
public class PublicLegalPageController {

    private final LegalDocumentService legalDocumentService;
    private final Parser markdownParser = Parser.builder().build();
    private final HtmlRenderer htmlRenderer = HtmlRenderer.builder().build();

    public PublicLegalPageController(LegalDocumentService legalDocumentService) {
        this.legalDocumentService = legalDocumentService;
    }

    @GetMapping(value = "/privacy-policy", produces = "text/html; charset=UTF-8")
    public String privacyPolicy() {
        return renderDocumentPage(LegalDocumentType.PRIVACY_POLICY, "Privacy Policy");
    }

    @GetMapping(value = "/terms-of-use", produces = "text/html; charset=UTF-8")
    public String termsOfUse() {
        return renderDocumentPage(LegalDocumentType.TERMS_AND_CONDITIONS, "Terms of Use");
    }

    /**
     * Play policy: apps that allow account creation must link a web page where
     * users can request account (and data) deletion without reinstalling the app.
     */
    @GetMapping(value = "/delete-account", produces = "text/html; charset=UTF-8")
    public String deleteAccount() {
        String body =
                "<h2>Delete your Wisemonie account</h2>"
                + "<p>Deleting your account closes it and removes your access to Wisemonie.</p>"
                + "<h3>Before you can delete</h3>"
                + "<p>Wisemonie is built to protect your money, so you can only close your "
                + "account once it holds no funds. First:</p>"
                + "<ol>"
                + "<li>Let any <strong>active budgets</strong> run to completion.</li>"
                + "<li>Wait for any <strong>savings pots</strong> to mature, then withdraw them.</li>"
                + "<li>Withdraw your <strong>wallet balance</strong> to your bank.</li>"
                + "</ol>"
                + "<p>Once your budgets, savings and wallet are all empty you can delete your "
                + "account, which guarantees you never lose money on deletion. If you try to "
                + "delete while funds are still held, we will ask you to clear them first.</p>"
                + "<h3>From the app</h3>"
                + "<ol>"
                + "<li>Open the Wisemonie app and sign in.</li>"
                + "<li>Go to <strong>More &rarr; Profile &amp; Account</strong>.</li>"
                + "<li>Choose <strong>Delete Account</strong> and follow the confirmation steps.</li>"
                + "</ol>"
                + "<h3>By email</h3>"
                + "<p>Send a deletion request to "
                + "<a href=\"mailto:wisemoniehelpdesk@gmail.com\">wisemoniehelpdesk@gmail.com</a> "
                + "from the email address registered to your account, with the subject "
                + "“Account deletion request”. We will verify the request and confirm once completed.</p>"
                + "<h3>What happens to your data</h3>"
                + "<p>Your account is closed and your access to the app is removed. As a "
                + "financial service, we are required to retain certain account and transaction "
                + "records for the period mandated by Nigerian financial regulations and our "
                + "legal obligations. See our <a href=\"/privacy-policy\">Privacy Policy</a> for "
                + "full details on what we keep and for how long.</p>";
        return pageShell("Delete your account", body, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String renderDocumentPage(LegalDocumentType type, String fallbackTitle) {
        LegalDocumentResponse doc = legalDocumentService.getActiveDocument(type);
        String bodyHtml = htmlRenderer.render(markdownParser.parse(doc.content()));
        String title = doc.title() != null && !doc.title().isBlank() ? doc.title() : fallbackTitle;
        String effective = doc.effectiveAt() != null
                ? doc.effectiveAt().format(DateTimeFormatter.ofPattern("d MMMM yyyy"))
                : null;
        return pageShell(title, bodyHtml, doc.version(), effective);
    }

    private String pageShell(String title, String bodyHtml, String version, String effectiveDate) {
        StringBuilder meta = new StringBuilder();
        if (version != null && !version.isBlank()) {
            meta.append("Version ").append(version);
        }
        if (effectiveDate != null) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append("Effective ").append(effectiveDate);
        }

        return "<!doctype html>"
                + "<html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + title + " — Wisemonie</title>"
                + "<style>"
                + "body{margin:0;background:#F4F8F6;color:#1E2B28;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "line-height:1.65;-webkit-text-size-adjust:100%;}"
                + "main{max-width:760px;margin:0 auto;padding:32px 20px 64px;}"
                + ".card{background:#fff;border:1px solid #E2ECE8;border-radius:16px;padding:32px 28px;}"
                + "header{display:flex;align-items:center;gap:12px;margin-bottom:24px;}"
                + "header img{height:40px;width:auto;}"
                + "h1,h2,h3{color:#0A3A3C;line-height:1.3;}"
                + "a{color:#0F6E56;}"
                + "li{margin:4px 0;}"
                + ".meta{color:#6B7C77;font-size:14px;margin-bottom:20px;}"
                + "footer{color:#6B7C77;font-size:13px;text-align:center;margin-top:28px;}"
                + "</style></head><body><main>"
                + "<header><img src=\"/images/wisemonie-logo.png\" alt=\"Wisemonie\"></header>"
                + "<div class=\"card\">"
                + (meta.length() > 0 ? "<p class=\"meta\">" + meta + "</p>" : "")
                + bodyHtml
                + "</div>"
                + "<footer>Wisemonie Digital Technologies Limited · RC 9578392 · "
                + "<a href=\"/privacy-policy\">Privacy Policy</a> · "
                + "<a href=\"/terms-of-use\">Terms of Use</a> · "
                + "<a href=\"/delete-account\">Delete account</a></footer>"
                + "</main></body></html>";
    }
}
