package trader.arbitrage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceService {

    private final MexcWebSocketService webSocketService;
    private final ObjectMapper objectMapper;
    private final Map<String, TokenPrice> lastPrices = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void initAfterStartup() {
        try {
            log.info("Initializing PriceService...");
            // Initialize connection
            webSocketService.connect();

            // Subscribe to token prices for all configured tokens
            for (String token : webSocketService.getConfiguredTokens()) {
                subscribeAndLog(token);
            }
            log.info("PriceService initialization completed successfully");
        } catch (Exception e) {
            log.error("Failed to initialize PriceService: {}", e.getMessage(), e);
            // Don't throw the exception here - log it but allow the application to start
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
                    .timestamp(Instant.ofEpochMilli(data.path("timestamp").asLong(System.currentTimeMillis())))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Error parsing ticker message: {}", e.getMessage());
            return null;
        }
    }
}
