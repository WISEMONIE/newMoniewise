package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.PayeelordDataPlan;
import com.moniewise.moniewise_backend.psp.payeelord.PayeelordGateway;
import com.moniewise.moniewise_backend.repository.PayeelordDataPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Periodically refreshes {@code payeelord_data_plans.cost_price} from Payeelord's
 * live price list — the data-side analogue of {@link ReconciliationScheduler}.
 *
 * <h3>⚠️ The actual scrape is NOT implemented yet — this is a scaffold</h3>
 * Payeelord does not document a catalog API; their integration notes say to
 * "open the dedicated tables page to copy" the price list by hand. That means
 * before {@link #scrapeCatalog()} can do real work, we need from the user/Payeelord:
 * <ul>
 *   <li>the exact page URL that lists current data plans (and whether it requires
 *       authentication — session cookie, API key in a query param, etc.)</li>
 *   <li>its structure — is it a rendered HTML table, a JSON endpoint behind the
 *       page, a downloadable CSV/PDF? This determines whether we even need an
 *       HTML parser (note: there is currently <b>no Jsoup or similar HTML-parsing
 *       library in {@code pom.xml}</b> — one would need to be added for table-scraping)</li>
 *   <li>a stable mapping from scraped rows back to Payeelord's {@code networkId}/
 *       {@code dataId} values (the identifiers {@code POST /buy/data} requires) —
 *       the price-list page may not expose these directly</li>
 * </ul>
 * Until then, {@link #scrapeCatalog()} throws {@link UnsupportedOperationException}
 * with this same explanation, the job logs a clear TODO and exits harmlessly, and
 * — critically — the feature defaults to <b>disabled</b>
 * ({@code system_config.payeelord.catalog.sync.enabled = false}, see the seed row
 * in {@code V13__create_payeelord_vas_tables.sql}) so nothing runs until both the
 * scrape target is known AND an admin deliberately flips the flag on.
 *
 * <h3>What IS fully built — the upsert pipeline around the seam</h3>
 * Once {@link #scrapeCatalog()} returns real {@link ScrapedPlan} rows, the rest of
 * this job is ready to go:
 * <ul>
 *   <li><b>Upserts {@code cost_price}</b> for existing plans (matched by {@code dataId})
 *       and inserts new ones — this column is scraper-owned, see
 *       {@link PayeelordDataPlan} column-ownership notes</li>
 *   <li><b>NEVER touches {@code markup_amount}</b> — that's the admin's margin and
 *       must survive every refresh untouched</li>
 *   <li><b>Deactivates</b> ({@code active = false}) plans that disappeared from the
 *       scrape — so the picker ({@code GET /vas/data/plans}) stops surfacing
 *       discontinued bundles without ever deleting the audit trail they're
 *       referenced from ({@code payeelord_vas_transactions.data_plan_id})</li>
 *   <li>Stamps {@code last_synced_at} on every row touched this run</li>
 * </ul>
 */
@Component
public class PayeelordCatalogSyncJob {

    private static final Logger logger = LoggerFactory.getLogger(PayeelordCatalogSyncJob.class);

    /** Networks we actually sell data for — MTN/GLO/9MOBILE/AIRTEL. SMILE(5)/SPECTRANET(6) are out of scope. */
    private static final Set<String> SUPPORTED_NETWORK_IDS = Set.of("1", "2", "3", "4");

    /** Pulls a data size like "500.0MB" / "1GB" / "1.5 GB" from the plan description. */
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|TB))", Pattern.CASE_INSENSITIVE);
    /** Pulls a validity like "30 days" / "7 day" from the plan description. */
    private static final Pattern VALIDITY_PATTERN =
            Pattern.compile("(\\d+\\s*days?)", Pattern.CASE_INSENSITIVE);

    private final PayeelordDataPlanRepository dataPlanRepository;
    private final SystemConfigService systemConfig;
    private final PayeelordGateway gateway;

    public PayeelordCatalogSyncJob(PayeelordDataPlanRepository dataPlanRepository,
                                   SystemConfigService systemConfig,
                                   PayeelordGateway gateway) {
        this.dataPlanRepository = dataPlanRepository;
        this.systemConfig = systemConfig;
        this.gateway = gateway;
    }

    /**
     * Runs once immediately after the application has fully started (all DB connections
     * up, beans wired) so the data-plan catalog is always populated on first boot —
     * no manual Postman call or admin trigger needed. Runs in a background thread so
     * it does not delay the final startup phase.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        new Thread(() -> {
            logger.info("[PayeelordCatalogSync] Application ready — running startup catalog sync...");
            try {
                int count = syncNow();
                if (count > 0) {
                    logger.info("[PayeelordCatalogSync] Startup sync complete — {} plans loaded into catalog", count);
                } else {
                    logger.warn("[PayeelordCatalogSync] Startup sync pulled 0 plans — " +
                            "check PAYEELORD_API_KEY and PAYEELORD_BASE_URL are set correctly");
                }
            } catch (Exception e) {
                logger.error("[PayeelordCatalogSync] Startup sync failed — " +
                        "catalog may be empty until the nightly run at 03:00", e);
            }
        }, "payeelord-startup-sync").start();
    }

    /**
     * Forces a full catalog sync right now, bypassing the
     * {@code payeelord.catalog.sync.enabled} gate — for the admin
     * "populate plans now" endpoint. Returns the number of plans scraped.
     */
    public int syncNow() {
        List<ScrapedPlan> scraped = scrapeCatalog();
        if (scraped.isEmpty()) {
            logger.warn("[PayeelordCatalogSync] Manual sync scraped 0 plans — leaving catalog untouched.");
            return 0;
        }
        upsertCatalog(scraped);
        return scraped.size();
    }

    /**
     * Runs at 03:00 Africa/Lagos by default — an hour after {@link ReconciliationScheduler}'s
     * 02:00 run, to keep heavier scheduled jobs from overlapping. Configurable via
     * {@code payeelord.catalog.sync.cron} for ops flexibility, exactly like the
     * reconciliation scheduler's {@code moniewise.reconciliation.scheduler.cron}.
     */
    @Scheduled(cron = "${payeelord.catalog.sync.cron:0 0 3 * * ?}", zone = "Africa/Lagos")
    public void runCatalogSync() {
        if (!systemConfig.getBoolean(SystemConfigService.PAYEELORD_CATALOG_SYNC_ENABLED, true)) {
            logger.debug("[PayeelordCatalogSync] Disabled via system_config.{} — skipping run",
                    SystemConfigService.PAYEELORD_CATALOG_SYNC_ENABLED);
            return;
        }

        logger.info("[PayeelordCatalogSync] Starting catalog sync run");

        List<ScrapedPlan> scraped;
        try {
            scraped = scrapeCatalog();
        } catch (UnsupportedOperationException e) {
            logger.warn("[PayeelordCatalogSync] Skipped — scraper not yet implemented: {}", e.getMessage());
            return;
        } catch (Exception e) {
            logger.error("[PayeelordCatalogSync] Scrape failed — leaving existing catalog untouched", e);
            return;
        }

        if (scraped == null || scraped.isEmpty()) {
            logger.warn("[PayeelordCatalogSync] Scrape returned no plans — refusing to deactivate the " +
                    "entire catalog on what's more likely a scrape failure than a real empty price list. " +
                    "Leaving existing rows untouched.");
            return;
        }

        upsertCatalog(scraped);
    }

    /**
     * Applies a freshly-scraped price list to the local catalog: upserts
     * {@code cost_price} (by {@code dataId}), inserts unseen plans, deactivates
     * ones no longer present, and stamps {@code last_synced_at} throughout.
     *
     * <p>Package-private + transactional so it can be unit-tested directly with a
     * hand-built {@code List<ScrapedPlan>} once the scraper exists, without needing
     * to mock the HTTP/HTML layer.
     */
    @Transactional
    void upsertCatalog(List<ScrapedPlan> scraped) {
        LocalDateTime now = LocalDateTime.now();
        Set<String> seenDataIds = new HashSet<>();
        int created = 0;
        int updated = 0;

        for (ScrapedPlan sp : scraped) {
            if (sp.dataId() == null || sp.dataId().isBlank() || sp.costPrice() == null) {
                logger.warn("[PayeelordCatalogSync] Skipping malformed scrape row: {}", sp);
                continue;
            }
            seenDataIds.add(sp.dataId());

            PayeelordDataPlan plan = dataPlanRepository.findByDataId(sp.dataId()).orElseGet(PayeelordDataPlan::new);
            boolean isNew = plan.getId() == null;

            plan.setDataId(sp.dataId());
            plan.setNetworkId(sp.networkId());
            plan.setNetworkName(sp.networkName());
            plan.setPlanType(sp.planType());
            plan.setPlanName(sp.planName());
            plan.setSizeLabel(sp.sizeLabel());
            plan.setValidityLabel(sp.validityLabel());

            // ── cost_price: scraper-owned, always overwritten ────────────────
            plan.setCostPrice(sp.costPrice());
            // ── markup_amount: ADMIN-OWNED — never touched here. New rows keep
            //    the entity default of BigDecimal.ZERO until an admin sets one. ──

            plan.setActive(true);
            plan.setLastSyncedAt(now);
            plan.setUpdatedAt(now);
            if (isNew) {
                plan.setCreatedAt(now);
            }

            dataPlanRepository.save(plan);
            if (isNew) created++; else updated++;
        }

        int deactivated = 0;
        for (PayeelordDataPlan existing : dataPlanRepository.findAll()) {
            if (existing.isActive() && !seenDataIds.contains(existing.getDataId())) {
                existing.setActive(false);
                existing.setLastSyncedAt(now);
                existing.setUpdatedAt(now);
                dataPlanRepository.save(existing);
                deactivated++;
            }
        }

        logger.info("[PayeelordCatalogSync] Sync complete — created={} updated={} deactivated={} (scraped {} rows)",
                created, updated, deactivated, scraped.size());
    }

    /**
     * THE SEAM — replace this with a real implementation once the scrape target is known.
     *
     * <p>See the class Javadoc for exactly what's missing (URL, structure, auth,
     * and a {@code networkId}/{@code dataId} mapping). Until then this throws so the
     * caller can log-and-skip cleanly rather than silently doing nothing forever.
     *
     * <p>Likely shape of a real implementation, once unblocked:
     * <pre>
     *   1. Fetch the price-list page/endpoint (Jsoup for HTML, RestTemplate for JSON/CSV —
     *      whichever the confirmed structure calls for; a parsing dependency may need
     *      adding to pom.xml)
     *   2. Parse each row into a {@link ScrapedPlan}, resolving networkId/dataId via
     *      whatever stable mapping the user/Payeelord confirms
     *   3. Return the full list — {@link #upsertCatalog} handles the rest
     * </pre>
     */
    List<ScrapedPlan> scrapeCatalog() {
        // 1. networkId → networkName (only the networks we sell data for)
        Map<String, String> networkNames = new HashMap<>();
        List<Map<String, Object>> networkList = gateway.getNetworks();
        logger.info("[PayeelordCatalogSync] /all-network → {} items. Sample: {}",
                networkList.size(), networkList.isEmpty() ? "none" : networkList.get(0));
        for (Map<String, Object> n : networkList) {
            // Payeelord may return snake_case or camelCase — try both
            String id   = coalesce(n, "network_id", "networkId", "id");
            String name = coalesce(n, "network_name", "networkName", "name");
            if (id != null && name != null) networkNames.put(id, name.toUpperCase());
        }

        // 2. all datatypes, grouped by network
        List<Map<String, Object>> dataTypes = gateway.getDataTypes();
        logger.info("[PayeelordCatalogSync] /datatypes → {} items. Sample: {}",
                dataTypes.size(), dataTypes.isEmpty() ? "none" : dataTypes.get(0));
        if (dataTypes.isEmpty()) {
            logger.warn("[PayeelordCatalogSync] /datatypes returned nothing — aborting this run.");
            return List.of();
        }

        List<ScrapedPlan> out = new ArrayList<>();
        boolean loggedPlanSample = false;
        for (Map<String, Object> dt : dataTypes) {
            // Payeelord may return snake_case or camelCase — try both
            String networkId = coalesce(dt, "network_id", "networkId");
            String dataType  = coalesce(dt, "type", "dataType", "plan_type", "planType");
            if (networkId == null || dataType == null) {
                logger.warn("[PayeelordCatalogSync] Datatype row missing networkId or type — keys={} values={}",
                        dt.keySet(), dt);
                continue;
            }
            if (!SUPPORTED_NETWORK_IDS.contains(networkId)) continue;

            String networkName = networkNames.getOrDefault(networkId, networkId);

            // 3. plans for this (dataType, network)
            List<Map<String, Object>> plans = gateway.getDataPlans(dataType, networkId);
            if (!loggedPlanSample && !plans.isEmpty()) {
                logger.info("[PayeelordCatalogSync] /data-plan first item sample (dataType={} networkId={}): {}",
                        dataType, networkId, plans.get(0));
                loggedPlanSample = true;
            }
            for (Map<String, Object> plan : plans) {
                // Try both camelCase and snake_case for every field
                String dataId = coalesce(plan, "dataId", "data_id", "id");
                String rawAmt = coalesce(plan, "amount", "price", "cost", "plan_amount", "planAmount");
                BigDecimal cost = parseNaira(rawAmt);
                if (dataId == null || cost == null) {
                    logger.warn("[PayeelordCatalogSync] Plan row missing dataId or amount — keys={}", plan.keySet());
                    continue;
                }

                String description = coalesce(plan, "description", "planName", "plan_name", "name", "dataName");
                String planName = (description != null && !description.isBlank()) ? description : dataType;

                out.add(new ScrapedPlan(
                        networkId,
                        networkName,
                        dataId,
                        dataType,                       // planType (e.g. "SME")
                        planName,                       // human label
                        extractSize(description),       // sizeLabel (e.g. "500.0MB")
                        extractValidity(description),   // validityLabel (e.g. "30 days")
                        cost                            // costPrice — Payeelord's price
                ));
            }
        }

        logger.info("[PayeelordCatalogSync] Scraped {} plans across {} datatypes", out.size(), dataTypes.size());
        return out;
    }

    /** Returns the first non-null/non-blank value found under any of the given keys. */
    private static String coalesce(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String val = str(map.get(key));
            if (val != null) return val;
        }
        return null;
    }

    // ── Parse helpers ───────────────────────────────────────────────────────

    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** "₦ 150" / "N150" / "150.00" → 150.00; null if unparseable. */
    private static BigDecimal parseNaira(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9.]", "");
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractSize(String description) {
        if (description == null) return null;
        Matcher m = SIZE_PATTERN.matcher(description);
        return m.find() ? m.group(1).replaceAll("\\s+", "") : null;
    }

    private static String extractValidity(String description) {
        if (description == null) return null;
        Matcher m = VALIDITY_PATTERN.matcher(description);
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /**
     * One row of freshly-scraped catalog data — exactly the fields
     * {@link #upsertCatalog} needs to write into {@link PayeelordDataPlan},
     * deliberately excluding {@code markupAmount} (admin-owned, never scraped).
     */
    public record ScrapedPlan(
            String networkId,
            String networkName,
            String dataId,
            String planType,
            String planName,
            String sizeLabel,
            String validityLabel,
            BigDecimal costPrice
    ) {}
}
