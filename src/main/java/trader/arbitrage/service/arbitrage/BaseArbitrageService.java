package trader.arbitrage.service.arbitrage;

import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.arbitrage.metricscounter.ArbitrageOpportunityProvider;
import trader.arbitrage.telegram.TelegramNotificationService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Base class for arbitrage services providing common functionality
 */
@Slf4j
public abstract class BaseArbitrageService implements ArbitrageOpportunityProvider {

    protected final TelegramNotificationService telegramService;
    protected final Counter arbitrageOpportunityCounter;
    protected final Counter telegramNotificationsCounter;

    @Value("${arbitrage.threshold}")
    protected double arbitrageThreshold;

    @Value("${arbitrage.check-interval}")
    protected long checkIntervalMs;

    // Keep track of detected opportunities
    protected final Map<String, ArbitrageOpportunity> lastDetectedOpportunities = new ConcurrentHashMap<>();

    protected BaseArbitrageService(
            TelegramNotificationService telegramService,
            Counter arbitrageOpportunityCounter,
            Counter telegramNotificationsCounter) {
        this.telegramService = telegramService;
        this.arbitrageOpportunityCounter = arbitrageOpportunityCounter;
        this.telegramNotificationsCounter = telegramNotificationsCounter;
    }

    @Scheduled(fixedRateString = "${arbitrage.check-interval}")
    public abstract void checkForArbitrageOpportunities();

    /**
     * Template method that defines the arbitrage opportunity checking algorithm
     */
    protected void checkForArbitrageOpportunities(
            Map<String, TokenPrice> primaryExchangePrices,
            Map<String, TokenPrice> secondaryExchangePrices,
            String primaryExchangeName,
            String secondaryExchangeName) {

        log.info("Checking for arbitrage opportunities between {} and {}...",
                primaryExchangeName, secondaryExchangeName);

        if (primaryExchangePrices.isEmpty() || secondaryExchangePrices.isEmpty()) {
            log.debug("Not enough price data available yet to check for arbitrage opportunities");
            return;
        }

        // Get common tokens between both exchanges
        Set<String> commonTokens = primaryExchangePrices.keySet().stream()
                .filter(secondaryExchangePrices::containsKey)
                .collect(Collectors.toSet());

        if (commonTokens.isEmpty()) {
            log.debug("No common tokens found between exchanges");
            return;
        }

        // Check each common token for price difference
        for (String token : commonTokens) {
            TokenPrice primaryPrice = primaryExchangePrices.get(token);
            TokenPrice secondaryPrice = secondaryExchangePrices.get(token);

            // Skip if either price is null
            if (primaryPrice == null || secondaryPrice == null ||
                    primaryPrice.getPrice() == null || secondaryPrice.getPrice() == null) {
                continue;
            }

            // Calculate price difference percentage
            BigDecimal priceDiffPercent = calculatePriceDifferencePercent(
                    primaryPrice.getPrice(), secondaryPrice.getPrice());

            // Check if difference exceeds threshold
            if (priceDiffPercent.abs().doubleValue() >= arbitrageThreshold) {
                if (arbitrageOpportunityCounter != null) {
                    arbitrageOpportunityCounter.increment();
                }

                // Create arbitrage opportunity object
                ArbitrageOpportunity opportunity = ArbitrageOpportunity.builder()
                        .symbol(token)
                        .mexcPrice(primaryPrice.getPrice())
                        .secondExchangePrice(secondaryPrice.getPrice())
                        .priceDifferencePercent(priceDiffPercent)
                        .secondExchangeName(secondaryPrice.getExchange())
                        .timestamp(LocalDateTime.now())
                        .build();

                // Log the opportunity
                logArbitrageOpportunity(opportunity, primaryExchangeName, secondaryExchangeName);

                // Process and notify about the opportunity
                processArbitrageOpportunity(opportunity);

                // Save for future reference
                lastDetectedOpportunities.put(token, opportunity);
            }
        }
    }

    /**
     * Process the detected arbitrage opportunity (e.g., send notifications)
     * Can be overridden by subclasses if needed
     */
    protected void processArbitrageOpportunity(ArbitrageOpportunity opportunity) {
        telegramService.sendArbitrageNotification(opportunity)
                .subscribe(
                        sent -> {
                            if (sent) {
                                if (telegramNotificationsCounter != null) {
                                    telegramNotificationsCounter.increment();
                                }
                                log.info("Telegram notification sent for arbitrage opportunity: {}", opportunity.getSymbol());
                            }
                        },
                        error -> log.error("Error sending Telegram notification: {}", error.getMessage())
                );
    }

    /**
     * Calculate percentage difference between two prices
     */
    protected BigDecimal calculatePriceDifferencePercent(BigDecimal price1, BigDecimal price2) {
        if (price2.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO; // Avoid division by zero
        }

        return price1.subtract(price2)
                .divide(price2, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Log arbitrage opportunity details
     */
    protected void logArbitrageOpportunity(
            ArbitrageOpportunity opportunity,
            String primaryExchangeName,
            String secondaryExchangeName) {

        String direction = opportunity.getPriceDifferencePercent().compareTo(BigDecimal.ZERO) > 0
                ? primaryExchangeName + " > " + secondaryExchangeName
                : secondaryExchangeName + " > " + primaryExchangeName;

        log.info("🚨 ARBITRAGE OPPORTUNITY DETECTED 🚨");
        log.info("Token: {}", opportunity.getSymbol());
        log.info("{} Price: {}", primaryExchangeName, opportunity.getMexcPrice());
        log.info("{} Price: {}", secondaryExchangeName, opportunity.getSecondExchangePrice());
        log.info("Price Difference: {}%", opportunity.getPriceDifferencePercent().abs());
        log.info("Direction: {}", direction);
        log.info("Timestamp: {}", opportunity.getTimestamp());
        log.info("--------------------------------------");
    }

    /**
     * Get all detected arbitrage opportunities
     */
    @Override
    public List<ArbitrageOpportunity> getAllArbitrageOpportunities() {
        return new ArrayList<>(lastDetectedOpportunities.values());
    }

    /**
     * Get most recent arbitrage opportunity for a specific token
     */
    public ArbitrageOpportunity getArbitrageOpportunity(String token) {
        return lastDetectedOpportunities.get(token);
    }
}