package trader.arbitrage.service.priceservice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.client.DexScreenerClient;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.clickhouse.ClickHouseService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DexScreenerService {

    private final DexScreenerClient dexScreenerClient;
    private final ClickHouseService clickHouseService;
    private final Map<String, TokenPrice> lastPrices = new ConcurrentHashMap<>();
    private final List<String> tokens;

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterStartup() {
        try {
            log.info("Initializing DexScreenerService...");
            for (String token : tokens) {
                subscribeAndLog(token);
            }
            log.info("DexScreenerService initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize DexScreenerService: {}", e.getMessage(), e);
        }
    }

    public Flux<TokenPrice> subscribeToTokenPrice(String token) {
        log.info("Subscribing to DEXScreener token price for: {}", token);
        return dexScreenerClient.getPriceStream(token);
    }

    private void subscribeAndLog(String token) {
        subscribeToTokenPrice(token)
                .subscribe(
                        price -> {
                            if (price != null) {
                                lastPrices.put(token, price);
                                logLastPrice(token);
                            }
                        },
                        error -> log.error("Error in DEXScreener price subscription for {}: {}", token, error.getMessage())
                );
    }

    public void logLastPrice(String token) {
        TokenPrice price = lastPrices.get(token);
        if (price != null) {
            log.info("Latest DEXScreener price for {}: {} at {}",
                    price.getSymbol(),
                    price.getPrice(),
                    price.getTimestamp());
            clickHouseService.bufferPrice(price);
        } else {
            log.info("No DEXScreener price data received yet for {}", token);
        }
    }

    public void logAllLastPrices() {
        if (lastPrices.isEmpty()) {
            log.info("No DEXScreener price data received yet for any token");
            return;
        }

        lastPrices.forEach((token, price) ->
                log.info("Latest DEXScreener price for {}: {} at {}",
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