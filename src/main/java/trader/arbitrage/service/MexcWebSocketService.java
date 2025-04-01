package trader.arbitrage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
@RequiredArgsConstructor
public class MexcWebSocketService {
    private final WebSocketClient coinCapClient;
    private final ObjectMapper objectMapper;
    private final List<String> tokens;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();
    private final AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>();

    @Value("${mexc.websocket.url}")
    private String mexcWebSocketUrl;

    @Value("${mexc.websocket.ping-interval:15}")
    private int pingInterval;

    // Создаем потоки для токенов и подключаемся вручную, а не через @PostConstruct
    // Это позволит избежать проблем с порядком инициализации в Spring
    public void initializeService() {
        log.info("Manually initializing MEXC WebSocket Service");
        tokens.forEach(this::createPriceStream);
        connectToMexcWebSocket();
    }

    // Этот метод нужно будет вызвать из другого компонента
    // Например, из ArbitrageService после его инициализации
    @PostConstruct
    public void init() {
        // Отложенный запуск, чтобы все зависимости точно были инициализированы
        Mono.delay(Duration.ofSeconds(2))
                .subscribe(i -> initializeService());
    }

    private void createPriceStream(String token) {
        priceStreams.put(token, Sinks.many().multicast().onBackpressureBuffer());
    }

    public Flux<TokenPrice> getPriceStream(String token) {
        return priceStreams.getOrDefault(token, Sinks.many().multicast().onBackpressureBuffer()).asFlux();
    }

    private void connectToMexcWebSocket() {
        URI uri = URI.create(mexcWebSocketUrl);
        log.info("Connecting to MEXC WebSocket at: {}", mexcWebSocketUrl);

        coinCapClient.execute(uri, session -> {
            sessionRef.set(session);
            log.info("WebSocket connection established");

            // Отправляем подписки после установки соединения
            return sendSubscriptions(session)
                    .then(setupPingMessages(session))
                    .then(session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .doOnNext(this::handleWebSocketMessage)
                            .doOnError(error -> {
                                log.error("Error in WebSocket connection: {}", error.getMessage());
                                reconnect();
                            })
                            .doOnComplete(() -> {
                                log.info("WebSocket connection completed, reconnecting...");
                                reconnect();
                            })
                            .then());
        }).subscribe(
                null,
                error -> {
                    log.error("Failed to connect to MEXC WebSocket: {}", error.getMessage());
                    // Добавляем задержку перед повторным подключением
                    Mono.delay(Duration.ofSeconds(5)).subscribe(i -> reconnect());
                }
        );
    }

    private Mono<Void> sendSubscriptions(WebSocketSession session) {
        // Подписываемся только на индивидуальные тикеры
        return Flux.fromIterable(tokens)
                .flatMap(token -> {
                    String symbol = token.toUpperCase() + "_USDT";
                    String subscriptionMessage = "{\"method\":\"sub.ticker\",\"param\":{\"symbol\":\"" + symbol + "\"}}";
                    log.info("Sending ticker subscription for {}: {}", symbol, subscriptionMessage);
                    return session.send(Mono.just(session.textMessage(subscriptionMessage)));
                })
                .then();
    }

    private Mono<Void> setupPingMessages(WebSocketSession session) {
        return Flux.interval(Duration.ofSeconds(pingInterval))
                .flatMap(i -> {
                    String pingMessage = "{\"method\":\"ping\"}";
                    log.debug("Sending ping to MEXC WebSocket");
                    return session.send(Mono.just(session.textMessage(pingMessage)))
                            .onErrorResume(e -> {
                                log.error("Failed to send ping: {}", e.getMessage());
                                reconnect();
                                return Mono.empty();
                            });
                })
                .then();
    }

    private void reconnect() {
        WebSocketSession currentSession = sessionRef.getAndSet(null);
        if (currentSession != null) {
            currentSession.close().subscribe();
        }

        // Добавляем задержку перед повторным подключением
        Mono.delay(Duration.ofSeconds(2))
                .subscribe(i -> connectToMexcWebSocket());
    }

    private void handleWebSocketMessage(String message) {
        try {
            log.debug("Received message: {}", message);
            JsonNode jsonNode = objectMapper.readTree(message);

            // Проверяем на pong-ответ
            if (jsonNode.has("channel") && "pong".equals(jsonNode.get("channel").asText())) {
                log.debug("Received pong from MEXC WebSocket");
                return;
            }

            // Обрабатываем данные тикера
            if (jsonNode.has("channel")) {
                String channel = jsonNode.get("channel").asText();

                if ("push.ticker".equals(channel) && jsonNode.has("data") && jsonNode.has("symbol")) {
                    // Обрабатываем данные для конкретного символа
                    JsonNode data = jsonNode.get("data");
                    String symbol = jsonNode.get("symbol").asText();
                    String baseSymbol = symbol.replace("_USDT", "");

                    if (data.has("lastPrice")) {
                        BigDecimal price = new BigDecimal(data.get("lastPrice").asText());

                        TokenPrice tokenPrice = TokenPrice.builder()
                                .symbol(baseSymbol)
                                .price(price)
                                .exchange("MEXC")
                                .timestamp(LocalDateTime.now())
                                .build();

                        if (priceStreams.containsKey(baseSymbol)) {
                            priceStreams.get(baseSymbol).tryEmitNext(tokenPrice);
                            log.info("MEXC price update for {}: {}", baseSymbol, price);
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing MEXC WebSocket message: {}", message, e);
        } catch (Exception e) {
            log.error("Error processing MEXC WebSocket message: {}", e.getMessage());
        }
    }
}