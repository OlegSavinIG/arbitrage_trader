package trader.arbitrage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArbitrageService {

    private final MexcPriceService mexcPriceService;
    private final CoinMarketCapService coinMarketCapClient;

    @Value("${arbitrage.threshold:0.01}")
    private double arbitrageThreshold; // Default is 1% (0.01)

    @Value("${arbitrage.check-interval:5000}")
    private long checkIntervalMs; // Default check interval is 10 seconds

    // Keep track of detected opportunities
    private final Map<String, ArbitrageOpportunity> lastDetectedOpportunities = new ConcurrentHashMap<>();

    /**
     * Scheduled method to check for arbitrage opportunities between exchanges
     */
    @Scheduled(fixedRateString = "${arbitrage.check-interval:10000}")
    public void checkForArbitrageOpportunities() {
        log.debug("Checking for arbitrage opportunities...");

        // Get all latest prices from both exchanges
        Map<String, TokenPrice> mexcPrices = getMexcLatestPrices();
        Map<String, TokenPrice> coinmarketcapPrices = coinMarketCapClient.getAllLatestPrices();

        if (mexcPrices.isEmpty() || coinmarketcapPrices.isEmpty()) {
            log.debug("Not enough price data available yet to check for arbitrage opportunities");
            return;
        }

        // Get common tokens between both exchanges
        Set<String> commonTokens = mexcPrices.keySet().stream()
                .filter(coinmarketcapPrices::containsKey)
                .collect(Collectors.toSet());

        if (commonTokens.isEmpty()) {
            log.debug("No common tokens found between exchanges");
            return;
        }

        // Check each common token for price difference
        for (String token : commonTokens) {
            TokenPrice mexcPrice = mexcPrices.get(token);
            TokenPrice coinmarketcapPrice = coinmarketcapPrices.get(token);

            // Skip if either price is null
            if (mexcPrice == null || coinmarketcapPrice == null ||
                    mexcPrice.getPrice() == null || coinmarketcapPrice.getPrice() == null) {
                continue;
            }

            // Calculate price difference percentage
            BigDecimal priceDiffPercent = calculatePriceDifferencePercent(
                    mexcPrice.getPrice(), coinmarketcapPrice.getPrice());

            // Check if difference exceeds threshold
            if (priceDiffPercent.abs().doubleValue() >= arbitrageThreshold) {
                // Create arbitrage opportunity object
                ArbitrageOpportunity opportunity = ArbitrageOpportunity.builder()
                        .symbol(token)
                        .mexcPrice(mexcPrice.getPrice())
                        .coincapPrice(coinmarketcapPrice.getPrice())
                        .priceDifferencePercent(priceDiffPercent)
                        .timestamp(LocalDateTime.now())
                        .build();

                // Log the opportunity
                logArbitrageOpportunity(opportunity);

                // Save for future reference
                lastDetectedOpportunities.put(token, opportunity);
            }
        }
    }

    /**
     * Calculate percentage difference between two prices
     */
    private BigDecimal calculatePriceDifferencePercent(BigDecimal price1, BigDecimal price2) {
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
    private void logArbitrageOpportunity(ArbitrageOpportunity opportunity) {
        String direction = opportunity.getPriceDifferencePercent().compareTo(BigDecimal.ZERO) > 0
                ? "MEXC > CoinMarketCap"
                : "CoinMarketCap > MEXC";

        log.info("🚨 ARBITRAGE OPPORTUNITY DETECTED 🚨");
        log.info("Token: {}", opportunity.getSymbol());
        log.info("MEXC Price: {}", opportunity.getMexcPrice());
        log.info("CoinMarketCap Price: {}", opportunity.getCoincapPrice());
        log.info("Price Difference: {}%", opportunity.getPriceDifferencePercent().abs());
        log.info("Direction: {}", direction);
        log.info("Timestamp: {}", opportunity.getTimestamp());
        log.info("--------------------------------------");
    }

    /**
     * Get all latest prices from MEXC
     */
    private Map<String, TokenPrice> getMexcLatestPrices() {
        // This is a placeholder - you need to implement a method in MexcPriceService
        // that returns all latest prices, similar to getAllLatestPrices() in CoinMarketCapService
        // For now, I'll create a simple implementation:

        Map<String, TokenPrice> result = new ConcurrentHashMap<>();

        // Get the configured tokens from the MEXC service
        for (String token : mexcPriceService.getConfiguredTokens()) {
            // This assumes you have or will add a getLastPrice method to MexcPriceService
            TokenPrice price = getLastPrice(token);
            if (price != null) {
                result.put(token, price);
            }
        }

        return result;
    }

    /**
     * Helper method to get last price for a token from MEXC
     * Note: You'll need to add this method to your MexcPriceService or access its lastPrices map
     */
    private TokenPrice getLastPrice(String token) {
        // This is a placeholder - implement actual logic to get the last price from MexcPriceService
        // For example, you could add a getLatestPrice(String token) method to MexcPriceService
        // that returns the last price for the given token
        return null; // Replace with actual implementation
    }

    /**
     * Get all detected arbitrage opportunities
     */
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
