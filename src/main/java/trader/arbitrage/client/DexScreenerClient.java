package trader.arbitrage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import trader.arbitrage.model.DexscreenerProperties;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DexScreenerClient {
    private final WebClient dexClient;
    //    private final List<String> tokens;
    private final DexscreenerProperties dexProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();

    @Value("${dexscreener.api.max-attempts:3}")
    private int maxAttempts;

    @Value("${dexscreener.api.initial-backoff:1000}")
    private long initialBackoffMillis;

    @Value("${dexscreener.api.max-backoff:10000}")
    private long maxBackoffMillis;

    private final AtomicInteger apiCallsInCurrentMinute = new AtomicInteger(0);
    private long currentMinuteStartTime = System.currentTimeMillis();

    @Value("${dexscreener.api.calls-per-minute:45}")
    private int maxCallsPerMinute;

    @PostConstruct
    public void init() {
        log.info("Dex properties {}", dexProperties);
        List<String> tokensList = dexProperties.getTokens().values()
                .stream()
                .flatMap(
                        tokens -> tokens.stream()
                                .map(DexscreenerProperties.Token::getSymbol))
                .toList();
        tokensList.forEach(this::createPriceStream);
        log.info("Price streams created for tokens on DexScreener: {}", tokensList);
    }

    private void createPriceStream(String token) {
        priceStreams.put(token, Sinks.many().multicast().onBackpressureBuffer());
    }

    public Flux<TokenPrice> getPriceStream(String token) {
        if (!priceStreams.containsKey(token)) {
            log.warn("Price stream for token {} not found in DexScreener client", token);
            return reactor.core.publisher.Flux.empty();
        }
        return priceStreams.get(token).asFlux();
    }

    @Scheduled(fixedRateString = "${dexscreener.api.update-interval}")
    public void fetchPrices() {
        checkAndResetRateLimit();

        if (apiCallsInCurrentMinute.get() >= maxCallsPerMinute) {
            log.warn("Rate limit reached ({} calls in current minute). Skipping this update cycle for DexScreener.",
                    apiCallsInCurrentMinute.get());
            return;
        }

        for (String chainID : dexProperties.getTokens().keySet()) {
            List<DexscreenerProperties.Token> tokens = dexProperties.getTokens().get(chainID);
            apiCallsInCurrentMinute.incrementAndGet();

            fetchTokenPrice(chainID, tokens)
                    .flatMapMany(Flux::fromIterable) // -> Flux<TokenPrice>
                    .doOnNext(tokenPrice -> {
                        String symbol = tokenPrice.getSymbol(); // пример: RFC_SOL
                        if (priceStreams.containsKey(symbol)) {
                            priceStreams.get(symbol).tryEmitNext(tokenPrice);
                            log.info("DexScreener price update for {}: {}", symbol, tokenPrice.getPrice());
                        } else {
                            log.debug("No subscriber found for symbol: {}", symbol);
                        }
                    })
                    .doOnError(error -> log.error("Failed to fetch price {}", error.getMessage()))
                    .subscribe();
        }
    }

    private void checkAndResetRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - currentMinuteStartTime;

        // Reset counter if a minute has passed
        if (timeElapsed >= 60000) { // 60 seconds in milliseconds
            log.debug("Resetting rate limit counter for DexScreener. Previous count: {}", apiCallsInCurrentMinute.get());
            apiCallsInCurrentMinute.set(0);
            currentMinuteStartTime = currentTime;
        }
    }

    /**
     * Fetches token price from DexScreener API
     *
     * @param tokens The token symbol to fetch price for
     * @return Mono with token price information
     */
    private Mono<List<TokenPrice>> fetchTokenPrice(String chainId, List<DexscreenerProperties.Token> tokens) {
        log.info("Fetching prices for symbols: {}",
                tokens.stream().map(DexscreenerProperties.Token::getSymbol).collect(Collectors.joining(", ")));

        return dexClient.get()
                .uri(buildRequestUri(chainId, tokens))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(createRetrySpec())
                .map(this::parseResponse)
                .onErrorResume(error -> {
                    log.error("Error fetching price from DexScreener for {}", error.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Builds the URI for the DEXScreener API request
     */
    private Function<UriBuilder, URI> buildRequestUri(String chainId, List<DexscreenerProperties.Token> tokens) {
        String tokenAddresses = tokens.stream()
                .map(DexscreenerProperties.Token::getAddress)
                .collect(Collectors.joining(","));

        return uriBuilder -> uriBuilder
                .path("/tokens/v1/" + chainId + "/" + tokenAddresses)
                .build();
    }

    /**
     * Creates a retry specification with exponential backoff
     */
    private RetryBackoffSpec createRetrySpec() {
        return Retry.backoff(maxAttempts, Duration.ofMillis(initialBackoffMillis))
                .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                .filter(this::shouldRetryOnError)
                .doBeforeRetry(retrySignal ->
                        log.info("Retrying DexScreener API call after error. Attempt {}/{}",
                                retrySignal.totalRetries() + 1, maxAttempts)
                );
    }

    /**
     * Determines if a request should be retried based on the error
     */
    private boolean shouldRetryOnError(Throwable throwable) {
        if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
            org.springframework.web.reactive.function.client.WebClientResponseException wcre =
                    (org.springframework.web.reactive.function.client.WebClientResponseException) throwable;
            int statusCode = wcre.getStatusCode().value();

            // Retry on rate limiting (429) and server errors (5xx)
            boolean shouldRetry = statusCode == 429 || (statusCode >= 500 && statusCode < 600);

            if (shouldRetry) {
                log.warn("Received status code {} from DexScreener API. Will retry.", statusCode);
            }

            return shouldRetry;
        }
        return false;
    }

    /**
     * Parses the API response to extract token price
     */
    private List<TokenPrice> parseResponse(String responseBody) {
        try {
            List<TokenPrice> prices = new ArrayList<>();

            JsonNode rootNode = objectMapper.readTree(responseBody);
            if (!rootNode.isArray() || rootNode.isEmpty()) {
                log.warn("Empty or invalid DexScreener response");
                return Collections.emptyList();
            }
            for (JsonNode pair : rootNode) {
                String pairSymbol = pair.get("baseToken").get("symbol").asText() + "_USDT"; // Standardize pair name
                BigDecimal price = extractPrice(pair);

                TokenPrice tokenPrice = TokenPrice.builder()
                        .symbol(pairSymbol)
                        .price(price)
                        .exchange("DEXScreener")
                        .timestamp(Instant.now())
                        .build();

                prices.add(tokenPrice);
            }

            return prices;

        } catch (Exception e) {
            log.error("Error parsing DexScreener response {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse DexScreener response", e);
        }
    }

    /**
     * Extracts the price from a pair node
     */
    private BigDecimal extractPrice(JsonNode pairNode) {
        if (pairNode.has("priceUsd")) {
            return new BigDecimal(pairNode.get("priceUsd").asText("0"));
        } else {
            // Fallback to regular price if USD price is not available
            return new BigDecimal(pairNode.get("price").asText("0"));
        }
    }
}
