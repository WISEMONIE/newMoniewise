package com.moniewise.moniewise_backend.psp;

import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.service.SystemConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes payment operations to the correct PSP gateway.
 *
 * <h3>PSP switching</h3>
 * The active default PSP is read from {@code system_config.psp.active} at every
 * call to {@link #resolveDefault()}. Update that one DB row — no redeploy needed.
 * The env var {@code ACTIVE_PSP} is the hard fallback if the DB row is absent.
 *
 * <h3>Per-wallet routing</h3>
 * {@link #resolveForWallet(Wallet)} always honours the {@code providerName} stamped
 * on the wallet at creation time, regardless of the current default. This means
 * existing SecureWave wallets continue to use SecureWave after a switch to Rubies.
 */
@Component
public class PaymentGatewayResolver {

    private static final Logger logger = LoggerFactory.getLogger(PaymentGatewayResolver.class);

    private final Map<String, PaymentGateway> gatewaysByProviderName;
    /** Hard fallback — used when system_config row is absent or DB is unreachable */
    private final String envFallbackProviderName;
    private final SystemConfigService systemConfig;

    public PaymentGatewayResolver(
            List<PaymentGateway> gateways,
            @Value("${moniewise.psp.default-provider:SECUREWAVE}") String defaultProviderName,
            @Value("${moniewise.psp.providus-integration-enabled:false}") boolean providusIntegrationEnabled,
            SystemConfigService systemConfig
    ) {
        this.systemConfig = systemConfig;

        this.gatewaysByProviderName = gateways.stream()
                .filter(gateway -> providusIntegrationEnabled
                        || !ProvidusExpressGateway.PROVIDER_NAME.equalsIgnoreCase(gateway.getProviderName()))
                .collect(Collectors.toMap(
                        gateway -> normalize(gateway.getProviderName()),
                        Function.identity()
                ));

        this.envFallbackProviderName = normalize(defaultProviderName);

        logger.info("[PSP] Registered gateways: {}", gatewaysByProviderName.keySet());
    }

    /**
     * Returns the gateway for the currently active PSP.
     *
     * <p>Reads {@code system_config.psp.active} from the DB (Redis-cached for 5 min).
     * Falls back to the {@code ACTIVE_PSP} env var if the config row is absent.
     */
    public PaymentGateway resolveDefault() {
        String activeProvider = systemConfig.getString(SystemConfigService.PSP_ACTIVE, envFallbackProviderName);
        String normalized = normalize(activeProvider);

        PaymentGateway gateway = gatewaysByProviderName.get(normalized);
        if (gateway != null) {
            return gateway;
        }

        // Config row points to an unknown/disabled gateway — fall back gracefully
        logger.warn("[PSP] system_config.psp.active='{}' has no registered gateway — falling back to '{}'",
                activeProvider, envFallbackProviderName);

        PaymentGateway fallback = gatewaysByProviderName.get(envFallbackProviderName);
        if (fallback == null) {
            throw new IllegalStateException(
                    "No payment gateway registered for '" + activeProvider + "' and fallback '" +
                    envFallbackProviderName + "' is also absent");
        }
        return fallback;
    }

    public PaymentGateway resolveByProviderName(String providerName) {
        String normalizedName = normalize(providerName);
        PaymentGateway gateway = gatewaysByProviderName.get(normalizedName);
        if (gateway != null) {
            return gateway;
        }

        logger.warn("[PSP] resolveByProviderName('{}') — not found, using default", providerName);
        return resolveDefault();
    }

    /**
     * Resolves by the provider stamped on the wallet.
     *
     * <p>Always honours the wallet's own {@code providerName} — so an existing
     * SecureWave wallet keeps using SecureWave even after the platform switches to Rubies.
     */
    public PaymentGateway resolveForWallet(Wallet wallet) {
        if (wallet == null || wallet.getProviderName() == null || wallet.getProviderName().isBlank()) {
            return resolveDefault();
        }
        return resolveByProviderName(wallet.getProviderName());
    }

    private String normalize(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return "";
        }
        return providerName.trim().toUpperCase(Locale.ROOT);
    }
}
