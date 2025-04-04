package trader.arbitrage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class MexcWebSocketService implements WebSocketHandler {

    //    private final MexcProperties mexcProperties;
    private final ObjectMapper objectMapper;
    private final Map<String, Sinks.Many<TokenPrice>> tokenPriceSinks = new ConcurrentHashMap<>();
    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    private WebSocketSession session;
    //    @Value("${mexc.wsUrl}")
    private final String wsUrl = "wss://contract.mexc.com/edge";

    //    @Value("#{'${mexc.tokens:BTCUSDT,ETHUSDT}'.split(',')}")
    private final List<String> tokens = List.of("BTC_USDT", "ETH_USDT");

    public void connect() {
        log.info("Connecting to MEXC WebSocket... {}", wsUrl);
        URI uri = URI.create(wsUrl);

        client.execute(uri, this)
                .subscribe(
                        success -> log.info("WebSocket connection established"),
                        error -> {
                            log.error("Error connecting to WebSocket: {}", error.getMessage());
                            reconnect();
                        },
                        () -> {
                            log.info("WebSocket connection closed");
                            reconnect();
                        }
                );
    }

    private void reconnect() {
        log.info("Attempting to reconnect in 5 seconds...");
        try {
            Thread.sleep(5000);
            connect();
        } catch (InterruptedException e) {
            log.error("Reconnection interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        this.session = session;

        // Subscribe to tokens from properties
        subscribeToTokens();

        // Setup ping scheduler
        setupPingScheduler();

        return session.receive()
                .doOnNext(this::handleMessage)
                .then();
    }

    private void handleMessage(WebSocketMessage message) {
        String payload = message.getPayloadAsText();
        log.debug("Received message: {}", payload);

        try {
            JsonNode rootNode = objectMapper.readTree(payload);
            String channel = rootNode.path("channel").asText();

            if ("push.ticker".equals(channel)) {
                handleTickerMessage(payload, rootNode);
            } else if ("pong".equals(channel)) {
                log.debug("Received pong response");
            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing message: {}", e.getMessage());
        }
    }

    private void handleTickerMessage(String payload, JsonNode rootNode) {
        try {
            JsonNode data = rootNode.path("data");
            String symbol = data.path("symbol").asText();

            TokenPrice price = TokenPrice.builder()
                    .symbol(symbol)
                    .price(new BigDecimal(data.path("lastPrice").asText("0")))
                    .timestamp(Instant.ofEpochMilli(data.path("timestamp").asLong(System.currentTimeMillis())))
                    .build();
            // Emit to the appropriate sink
            Sinks.Many<TokenPrice> sink = tokenPriceSinks.get(symbol);
            if (sink != null) {
                sink.tryEmitNext(price);
            }

            log.debug("Processed ticker for symbol: {}", symbol);
        } catch (Exception e) {
            log.error("Error processing ticker message: {}", e.getMessage());
        }
    }

    private void subscribeToTokens() {
        for (String token : tokens) {
            log.info("Subscribing to token: {}", token);

            // Create sink if it doesn't exist
            tokenPriceSinks.computeIfAbsent(token,
                    k -> Sinks.many().multicast().onBackpressureBuffer());

            // Subscribe to ticker for this token
            String subscribeMessage = String.format(
                    "{\"method\":\"sub.ticker\",\"param\":{\"symbol\":\"%s\"}}", token);

            session.send(Mono.just(session.textMessage(subscribeMessage)))
                    .subscribe(
                            null,
                            error -> log.error("Error subscribing to {}: {}", token, error.getMessage())
                    );
        }
    }

    private void setupPingScheduler() {
        Flux.interval(Duration.ofSeconds(15))
                .flatMap(i -> session.send(Mono.just(session.textMessage("{\"method\":\"ping\"}"))))
                .subscribe(
                        null,
                        error -> log.error("Error sending ping: {}", error.getMessage())
                );
    }

    public Flux<TokenPrice> getTokenPriceStream(String token) {
        return tokenPriceSinks.computeIfAbsent(token,
                k -> Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }

    public List<String> getConfiguredTokens() {
        return tokens;
    }
}