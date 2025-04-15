package trader.arbitrage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoinMarketCapClient {
    private final Counter apiCallsCounter;
    private final WebClient coinCapClient;
    private final List<String> tokens;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();

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
        checkAndResetRateLimit();

        if (apiCallsInCurrentMinute.get() >= maxCallsPerMinute) {
            log.warn("Rate limit reached ({} calls in current minute). Skipping this update cycle.",
                    apiCallsInCurrentMinute.get());
            return;
        }

        String symbols = tokens.stream()
                .map(token -> token.split("_")[0])
                .collect(Collectors.joining(","));

        // Increment API call counter before making the call
        apiCallsInCurrentMinute.incrementAndGet();
        apiCallsCounter.increment();

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

    /**
     * Получает цены токенов с API CoinMarketCap.
     * @param symbols Строка с символами токенов для запроса
     * @return Mono с картой соответствия символов токенов и их цен
     */
    private Mono<Map<String, TokenPrice>> fetchTokenPrice(String symbols) {
        log.info("Fetching price for symbols {}", symbols);
        return coinCapClient.get()
                .uri(buildRequestUri(symbols))
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(createRetrySpec())
                .map(this::parseResponse)
                .onErrorResume(this::handleError);
    }

    /**
     * Строит URI для запроса к API.
     */
    private Function<UriBuilder, URI> buildRequestUri(String symbols) {
        return uriBuilder -> uriBuilder
                .queryParam("symbol", symbols)
                .queryParam("convert", "USD")
                .build();
    }

    /**
     * Создает спецификацию повторных попыток с экспоненциальной задержкой.
     */
    private RetryBackoffSpec createRetrySpec() {
        return Retry.backoff(maxAttempts, Duration.ofMillis(initialBackoffMillis))
                .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                .filter(this::shouldRetryOnError)
                .doBeforeRetry(retrySignal ->
                        log.info("Retrying API call after error. Attempt {}/{}",
                                retrySignal.totalRetries() + 1, maxAttempts)
                );
    }

    /**
     * Определяет, следует ли повторить запрос при данной ошибке.
     */
    private boolean shouldRetryOnError(Throwable throwable) {
        if (throwable instanceof WebClientResponseException) {
            WebClientResponseException wcre = (WebClientResponseException) throwable;
            int statusCode = wcre.getStatusCode().value();

            // Повторяем при 429 (Too Many Requests) и определенных 5xx ошибках
            boolean shouldRetry = statusCode == 429 || (statusCode >= 500 && statusCode < 600);

            if (shouldRetry) {
                log.warn("Received status code {} from API. Will retry.", statusCode);
            }

            return shouldRetry;
        }
        return false;
    }

    /**
     * Разбор ответа API и преобразование в карту токен-цена.
     */
    private Map<String, TokenPrice> parseResponse(String responseBody) {
        try {
            Map<String, TokenPrice> result = new HashMap<>();
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            JsonNode data = jsonNode.get("data");

            validateApiResponse(jsonNode);
            processTokenData(data, result);

            return result;
        } catch (Exception e) {
            log.error("Error parsing CoinMarketCap response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to parse CoinMarketCap response", e);
        }
    }

    /**
     * Проверяет ответ API на наличие ошибок.
     */
    private void validateApiResponse(JsonNode jsonNode) {
        JsonNode statusNode = jsonNode.get("status");

        // Проверка на ошибки API
        if (statusNode != null && statusNode.has("error_code") && statusNode.get("error_code").asInt() != 0) {
            String errorMessage = statusNode.has("error_message") ?
                    statusNode.get("error_message").asText() :
                    "Unknown error in CoinMarketCap response";
            throw new RuntimeException("API Error: " + errorMessage);
        }

        if (jsonNode.get("data") == null) {
            throw new RuntimeException("Missing data in CoinMarketCap response");
        }
    }

    /**
     * Обрабатывает данные токенов из ответа API.
     */
    private void processTokenData(JsonNode dataNode, Map<String, TokenPrice> result) {
        dataNode.fields().forEachRemaining(entry -> {
            String symbol = entry.getKey();
            JsonNode tokenData = entry.getValue();

            JsonNode quoteNode = tokenData.get("quote").get("USD");
            BigDecimal price = new BigDecimal(quoteNode.get("price").asText());
            String tokenPair = symbol + "_USDT";

            TokenPrice tokenPrice = TokenPrice.builder()
                    .symbol(tokenPair)
                    .price(price)
                    .exchange("CoinMarketCap")
                    .timestamp(Instant.now())
                    .build();

            result.put(tokenPair, tokenPrice);
        });
    }

    /**
     * Обработка ошибок при запросе.
     */
    private Mono<Map<String, TokenPrice>> handleError(Throwable e) {
        log.error("Error fetching prices from CoinMarketCap: {}", e.getMessage(), e);
        return Mono.empty();
    }
}