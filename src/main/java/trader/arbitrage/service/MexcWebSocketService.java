package trader.arbitrage.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
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

@Service
@Slf4j
@RequiredArgsConstructor
public class MexcWebSocketService {
    private final WebSocketClient webSocketClient;
    private final ObjectMapper objectMapper;
    private final List<String> tokens;
    private final Map<String, Sinks.Many<TokenPrice>> priceStreams = new HashMap<>();

    @Value("${mexc.websocket.url}")
    private String mexcWebSocketUrl;

    @Value("${mexc.websocket.ping-interval:15}")
    private int pingInterval;

    @Value("${mexc.websocket.subscription-type:ticker}")
    private String subscriptionType;

    @PostConstruct
    public void init() {
        tokens.forEach(this::createPriceStream);
        connectToMexcWebSocket();
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

        webSocketClient.execute(uri, session -> {
            // Отправка сообщения подписки для каждого токена в зависимости от типа подписки
            for (String token : tokens) {
                String symbol = token.toUpperCase() + "_USDT";
                String subscriptionMessage;

                if ("ticker".equals(subscriptionType)) {
                    // Подписка на ticker для конкретного символа
                    subscriptionMessage = String.format("{\"method\":\"sub.ticker\",\"param\":{\"symbol\":\"%s\"}}", symbol);
                } else {
                    // Подписка на tickers для всех символов
                    subscriptionMessage = "{\"method\":\"sub.tickers\",\"param\":{}}";
                    // Если подписываемся на все tickers, делаем это только один раз
                    break;
                }

                log.debug("Sending subscription message: {}", subscriptionMessage);
                session.send(Mono.just(session.textMessage(subscriptionMessage))).subscribe();
            }

            // Обработка входящих сообщений
            return session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(this::handleWebSocketMessage)
                    .then();
        }).subscribe();

        // Пинг для поддержания соединения
        Flux.interval(Duration.ofSeconds(pingInterval))
                .doOnNext(i -> {
                    try {
                        log.debug("Sending ping to MEXC WebSocket");
                        webSocketClient.execute(uri, session ->
                                        session.send(Mono.just(session.textMessage("{\"method\":\"ping\"}"))).then())
                                .subscribe();
                    } catch (Exception e) {
                        log.error("Failed to send ping to MEXC WebSocket", e);
                        // Переподключение при ошибке
                        connectToMexcWebSocket();
                    }
                }).subscribe();
    }

    private void handleWebSocketMessage(String message) {
        try {
            JsonNode jsonNode = objectMapper.readTree(message);

            // Проверяем тип сообщения по полю "channel"
            if (jsonNode.has("channel")) {
                String channel = jsonNode.get("channel").asText();

                if ("pong".equals(channel)) {
                    // Обработка pong-ответа
                    log.debug("Received pong from MEXC WebSocket");
                    return;
                }

                if ("push.ticker".equals(channel) && jsonNode.has("data") && jsonNode.has("symbol")) {
                    // Обработка данных для конкретного символа
                    JsonNode data = jsonNode.get("data");
                    String symbol = jsonNode.get("symbol").asText();
                    String baseSymbol = symbol.replace("_USDT", "");

                    BigDecimal price = new BigDecimal(data.get("lastPrice").asText());

                    TokenPrice tokenPrice = TokenPrice.builder()
                            .symbol(baseSymbol)
                            .price(price)
                            .exchange("MEXC")
                            .timestamp(LocalDateTime.now())
                            .build();

                    if (priceStreams.containsKey(baseSymbol)) {
                        priceStreams.get(baseSymbol).tryEmitNext(tokenPrice);
                        log.debug("MEXC price update for {}: {}", baseSymbol, price);
                    }
                } else if ("push.tickers".equals(channel) && jsonNode.has("data")) {
                    // Обработка данных для всех символов
                    JsonNode dataArray = jsonNode.get("data");
                    if (dataArray.isArray()) {
                        for (JsonNode item : dataArray) {
                            String symbol = item.get("symbol").asText();
                            String baseSymbol = symbol.replace("_USDT", "");

                            // Проверяем, интересует ли нас этот токен
                            if (priceStreams.containsKey(baseSymbol)) {
                                BigDecimal price = new BigDecimal(item.get("lastPrice").asText());

                                TokenPrice tokenPrice = TokenPrice.builder()
                                        .symbol(baseSymbol)
                                        .price(price)
                                        .exchange("MEXC")
                                        .timestamp(LocalDateTime.now())
                                        .build();

                                priceStreams.get(baseSymbol).tryEmitNext(tokenPrice);
                                log.debug("MEXC price update for {}: {}", baseSymbol, price);
                            }
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
