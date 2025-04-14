package trader.arbitrage.config;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class TelegramWebClientConfiguration {
    private final Dotenv dotenv;

    @Bean
    public WebClient telegramWebClient(
            @Value("${telegram.api.url:https://api.telegram.org/bot}") String baseUrl,
//            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.api.connection.timeout:3000}") int connectionTimeoutMillis,
            @Value("${telegram.api.read.timeout:5000}") int readTimeoutMillis
    ) {
        String botToken = dotenv.get("TELEGRAM_BOT_TOKEN");


        // Create a connection provider with connection pooling
        ConnectionProvider provider = ConnectionProvider.builder("telegram-pool")
                .maxConnections(20)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        // Create an HTTP client with connection pooling and timeouts
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofMillis(readTimeoutMillis))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis);

        // Complete URL with bot token
        String apiUrl = baseUrl + botToken;

        return WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    // Logging filter for requests
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isDebugEnabled()) {
                log.debug("Telegram Request: {} {}", clientRequest.method(), clientRequest.url());
                clientRequest.headers().forEach((name, values) -> {
                    values.forEach(value -> log.debug("{}={}", name, value));
                });
            }
            return Mono.just(clientRequest);
        });
    }

    // Logging filter for responses
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (log.isDebugEnabled()) {
                log.debug("Telegram Response status: {}", clientResponse.statusCode());
            }
            return Mono.just(clientResponse);
        });
    }
}
