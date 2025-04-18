package trader.arbitrage.service.priceservice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.client.CoinMarketCapClient;
import trader.arbitrage.model.TokenPrice;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinMarketCapService {

    private final CoinMarketCapClient coinMarketCapPriceService;
    private final Map<String, TokenPrice> lastPrices = new ConcurrentHashMap<>();
    private final List<String> tokens;

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterStartup() {
        try {
            log.info("Initializing CoinMarketCapService...");
            for (String token : tokens) {
                subscribeAndLog(token);
            }
            log.info("CoinMarketCapService initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize CoinMarketCapService: {}", e.getMessage(), e);
        }
    }

    public Flux<TokenPrice> subscribeToTokenPrice(String token) {
        log.info("Subscribing to token price for: {}", token);
        return coinMarketCapPriceService.getPriceStream(token);
    }

    private void subscribeAndLog(String token) {
        subscribeToTokenPrice(token)
                .subscribe(
                        price -> {
                            lastPrices.put(token, price);
                            logLastPrice(token);
                        },
                        error -> log.error("Error in price subscription for {}: {}", token, error.getMessage())
                );
    }

    public void logLastPrice(String token) {
        TokenPrice price = lastPrices.get(token);
        if (price != null) {
            log.info("Latest CoinMarketCap price for {}: {} at {}",
                    price.getSymbol(),
                    price.getPrice(),
                    price.getTimestamp());
        } else {
            log.info("No CoinMarketCap price data received yet for {}", token);
        }
    }

    public void logAllLastPrices() {
        if (lastPrices.isEmpty()) {
            log.info("No CoinMarketCap price data received yet for any token");
            return;
        }

        lastPrices.forEach((token, price) ->
                log.info("Logging CoinMarketCap price for {}: {} at {}",
                        price.getSymbol(),
                        price.getPrice(),
                        price.getTimestamp())
        );
    }

    /**
     * Get the latest price for a specific token
     *
     * @param token The token symbol e.g., "BTC_USDT"
     * @return The latest TokenPrice object or null if not available
     */
    public TokenPrice getLatestPrice(String token) {
        return lastPrices.get(token);
    }

    /**
     * Get all the latest prices
     *
     * @return Map of token symbols to their latest prices
     */
    public Map<String, TokenPrice> getAllLatestPrices() {
        return new ConcurrentHashMap<>(lastPrices);
    }
}