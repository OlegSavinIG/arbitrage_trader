package trader.arbitrage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoinMarketCapPriceService {
    private final WebClient coinCapClient;
    private final List<String> tokens = List.of("BTC_USDT");
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();

    // Rate limiting parameters
    @Value("${coincap.api.max-attempts:3}")
    private int maxAttempts;

    @Value("${coincap.api.initial-backoff:1000}")
    private long initialBackoffMillis;

    @Value("${coincap.api.max-backoff:10000}")
    private long maxBackoffMillis;

    // Track API calls within time windows
    private final AtomicInteger apiCallsInCurrentMinute = new AtomicInteger(0);
    private long currentMinuteStartTime = System.currentTimeMillis();

    // CoinMarketCap typically has a rate limit of ~30 calls per minute
    @Value("${coincap.api.calls-per-minute:25}")
    private int maxCallsPerMinute;

    @PostConstruct
    public void init() {
        tokens.forEach(this::createPriceStream);
        log.info("Price streams created for tokens: {}", tokens);
    }

    private void createPriceStream(String token) {
        priceStreams.put(token, Sinks.many().multicast().onBackpressureBuffer());
    }

    public Flux<TokenPrice> getPriceStream(String token) {
        if (!priceStreams.containsKey(token)) {
            log.warn("Price stream for token {} not found", token);
            return Flux.empty();
        }
        return priceStreams.get(token).asFlux();
    }

    @Scheduled(fixedRateString = "${coincap.api.update-interval}")
    public void fetchPrices() {
        // Check rate limiting
        checkAndResetRateLimit();

        if (apiCallsInCurrentMinute.get() >= maxCallsPerMinute) {
            log.warn("Rate limit reached ({} calls in current minute). Skipping this update cycle.",
                    apiCallsInCurrentMinute.get());
            return;
        }

        // Extract base symbols (BTC, ETH) from token pairs (BTC_USDT, ETH_USDT)
        String symbols = tokens.stream()
                .map(token -> token.split("_")[0])
                .collect(Collectors.joining(","));

        log.debug("Fetching prices from CoinMarketCap for symbols: {}", symbols);

        // Increment API call counter before making the call
        apiCallsInCurrentMinute.incrementAndGet();

        fetchTokenPrice(symbols)
                .doOnNext(tokenPriceMap -> {
                    tokenPriceMap.forEach((token, price) -> {
                        if (priceStreams.containsKey(token)) {
                            priceStreams.get(token).tryEmitNext(price);
                            log.info("CoinMarketCap price update for {}: {}", token, price.getPrice());
                        }
                    });
                })
                .doOnError(error -> log.error("Failed to fetch prices after retries: {}", error.getMessage()))
                .subscribe();
    }

    private void checkAndResetRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - currentMinuteStartTime;

        // Reset counter if a minute has passed
        if (timeElapsed >= 60000) { // 60 seconds in milliseconds
            log.debug("Resetting rate limit counter. Previous count: {}", apiCallsInCurrentMinute.get());
            apiCallsInCurrentMinute.set(0);
            currentMinuteStartTime = currentTime;
        }
    }

    private Mono<Map<String, TokenPrice>> fetchTokenPrice(String symbols) {
        log.info("Fetching price for symbols {}", symbols);
        return coinCapClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/cryptocurrency/quotes/latest")
                        .queryParam("symbol", symbols)
                        .queryParam("convert", "USD")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                // Implement retry with exponential backoff
                .retryWhen(Retry.backoff(maxAttempts, Duration.ofMillis(initialBackoffMillis))
                        .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                        .filter(throwable -> {
                            // Only retry on specific errors that might be related to rate limiting
                            if (throwable instanceof WebClientResponseException) {
                                WebClientResponseException wcre = (WebClientResponseException) throwable;
                                int statusCode = wcre.getStatusCode().value();

                                // Retry on 429 (Too Many Requests) and certain 5xx errors
                                boolean shouldRetry = statusCode == 429 || (statusCode >= 500 && statusCode < 600);

                                if (shouldRetry) {
                                    log.warn("Received status code {} from API. Will retry.", statusCode);
                                }

                                return shouldRetry;
                            }
                            return false;
                        })
                        .doBeforeRetry(retrySignal ->
                                log.info("Retrying API call after error. Attempt {}/{}",
                                        retrySignal.totalRetries() + 1, maxAttempts)
                        )
                )
                .map(responseBody -> {
                    try {

                        Map<String, TokenPrice> result = new HashMap<>();
                        JsonNode jsonNode = objectMapper.readTree(responseBody);
                        JsonNode statusNode = jsonNode.get("status");

                        // Check for API errors
                        if (statusNode != null && statusNode.has("error_code") && statusNode.get("error_code").asInt() != 0) {
                            String errorMessage = statusNode.has("error_message") ?
                                    statusNode.get("error_message").asText() :
                                    "Unknown error in CoinMarketCap response";
                            throw new RuntimeException("API Error: " + errorMessage);
                        }

                        JsonNode dataNode = jsonNode.get("data");
                        if (dataNode == null) {
                            throw new RuntimeException("Missing data in CoinMarketCap response");
                        }

                        dataNode.fields().forEachRemaining(entry -> {
                            String symbol = entry.getKey();
                            JsonNode tokenData = entry.getValue();

                            // Get the quote in USD (which we'll treat as USDT)
                            JsonNode quoteNode = tokenData.get("quote").get("USD");
                            BigDecimal price = new BigDecimal(quoteNode.get("price").asText());
                            // Create token pair in format used by the application
                            String tokenPair = symbol + "_USDT";

                            TokenPrice tokenPrice = TokenPrice.builder()
                                    .symbol(tokenPair)
                                    .price(price)
                                    .exchange("CoinMarketCap")
                                    .timestamp(Instant.now())
                                    .build();
                            log.info("TokenPrice from response - {}", tokenPrice);
                            result.put(tokenPair, tokenPrice);
                        });

                        return result;
                    } catch (Exception e) {
                        log.error("Error parsing CoinMarketCap response: {}", e.getMessage(), e);
                        throw new RuntimeException("Failed to parse CoinMarketCap response", e);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error fetching prices from CoinMarketCap: {}", e.getMessage(), e);
                    return Mono.empty();
                });
    }
}