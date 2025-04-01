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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CoincapPriceService {
    private final WebClient webClient;
    private final List<String> tokens;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();

    @Value("${coincap.api.url}")
    private String coincapApiUrl;

    @PostConstruct
    public void init() {
        tokens.forEach(this::createPriceStream);
        log.info("Initialized CoinCap price service with API URL: {}", coincapApiUrl);
    }

    private void createPriceStream(String token) {
        priceStreams.put(token, Sinks.many().multicast().onBackpressureBuffer());
    }

    public Flux<TokenPrice> getPriceStream(String token) {
        return priceStreams.getOrDefault(token, Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }

    @Scheduled(fixedRateString = "${coincap.api.update-interval}")
    public void fetchPrices() {
        log.debug("Fetching prices from CoinCap");
        for (String token : tokens) {
            fetchTokenPrice(token)
                    .doOnNext(tokenPrice -> {
                        if (priceStreams.containsKey(token)) {
                            priceStreams.get(token).tryEmitNext(tokenPrice);
                            log.debug("CoinCap price update for {}: {}", token, tokenPrice.getPrice());
                        }
                    })
                    .subscribe();
        }
    }

    private Mono<TokenPrice> fetchTokenPrice(String token) {
        return webClient.get()
                .uri(coincapApiUrl + "/" + token.toLowerCase())
                .retrieve()
                .bodyToMono(String.class)
                .map(responseBody -> {
                    try {
                        JsonNode jsonNode = objectMapper.readTree(responseBody);
                        BigDecimal price = new BigDecimal(jsonNode.get("data").get("priceUsd").asText());

                        return TokenPrice.builder()
                                .symbol(token)
                                .price(price)
                                .exchange("CoinCap")
                                .timestamp(LocalDateTime.now())
                                .build();
                    } catch (Exception e) {
                        log.error("Error parsing CoinCap response for token {}: {}", token, e.getMessage());
                        throw new RuntimeException("Failed to parse CoinCap response", e);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Error fetching price for {} from CoinCap: {}", token, e.getMessage());
                    return Mono.empty();
                });
    }
}
