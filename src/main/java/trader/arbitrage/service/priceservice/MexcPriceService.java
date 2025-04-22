package trader.arbitrage.service.priceservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.client.MexcWebSocketClient;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.clickhouse.ClickHouseService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MexcPriceService {

    private final MexcWebSocketClient webSocketService;
    private final ClickHouseService clickHouseService;
    private final ObjectMapper objectMapper;
    private final Map<String, TokenPrice> lastPrices = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterStartup() {
        try {
            log.info("Initializing MexcPriceService...");
            webSocketService.connect();

            for (String token : webSocketService.getConfiguredTokens()) {
                subscribeAndLog(token);
            }
            log.info("MexcPriceService initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize MexcPriceService: {}", e.getMessage(), e);
        }
    }
    public Flux<TokenPrice> subscribeToTokenPrice(String token) {
        log.info("Subscribing to token price for: {}", token);
        return webSocketService.getTokenPriceStream(token);
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
            log.info("Latest price for {}: {} at {}",
                    price.getSymbol(),
                    price.getPrice(),
                    price.getTimestamp());
            clickHouseService.bufferPrice(price);
        } else {
            log.info("No price data received yet for {}", token);
        }
    }

    public void logAllLastPrices() {
        if (lastPrices.isEmpty()) {
            log.info("No price data received yet for any token");
            return;
        }

        lastPrices.forEach((token, price) ->
                log.info("Logging price for {}: {} at {}",
                        price.getSymbol(),
                        price.getPrice(),
                        price.getTimestamp())
        );
    }
    public TokenPrice getLatestPrice(String token) {
        return lastPrices.get(token);
    }

    // New method to get all latest prices
    public Map<String, TokenPrice> getAllLatestPrices() {
        return new ConcurrentHashMap<>(lastPrices);
    }

    // New method to get all configured tokens
    public List<String> getConfiguredTokens() {
        return webSocketService.getConfiguredTokens();
    }
    // This method would be called by the WebSocketService when messages are received
    public TokenPrice parseTokenPriceMessage(String message) {
        try {
            JsonNode rootNode = objectMapper.readTree(message);

            // Check if this is a ticker message
            if (!"push.ticker".equals(rootNode.path("channel").asText())) {
                return null;
            }

            JsonNode data = rootNode.path("data");

            return TokenPrice.builder()
                    .symbol(data.path("symbol").asText())
                    .price(new BigDecimal(data.path("lastPrice").asText("0")))
                    .exchange("MEXC")
                    .timestamp(Instant.ofEpochMilli(data.path("timestamp").asLong(System.currentTimeMillis())))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Error parsing ticker message: {}", e.getMessage());
            return null;
        }
    }
}
