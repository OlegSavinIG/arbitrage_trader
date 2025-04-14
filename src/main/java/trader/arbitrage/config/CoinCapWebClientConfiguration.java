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
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
@Slf4j
@RequiredArgsConstructor
public class CoinCapWebClientConfiguration {
    private final Dotenv dotenv;


    @Bean
    public WebClient coinCapClient(
            @Value("${coincap.api.url}") String baseUrl,
            @Value("${coincap.api.connection.timeout:3000}") int connectionTimeoutMillis,
            @Value("${coincap.api.read.timeout:5000}") int readTimeoutMillis,
            @Value("${coincap.api.max.memory.size:16777216}") int maxInMemorySize // 16MB default
    ) {
        String apiKey = dotenv.get("COINMARKETCAP_API_KEY");
        // Create a connection provider with connection pooling
        ConnectionProvider provider = ConnectionProvider.builder("coin-market-cap-pool")
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(30))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(45))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        // Create an HTTP client with connection pooling and timeouts
        HttpClient httpClient = HttpClient.create(provider)
                .responseTimeout(Duration.ofMillis(readTimeoutMillis))
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis);

        // Increase memory size for larger responses
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(maxInMemorySize))
                .build();


        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-CMC_PRO_API_KEY", apiKey)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(exchangeStrategies)
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    // Logging filter for requests
    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            if (log.isDebugEnabled()) {
                log.debug("Request: {} {}", clientRequest.method(), clientRequest.url());
                clientRequest.headers().forEach((name, values) -> {
                    if (!name.equalsIgnoreCase("X-CMC_PRO_API_KEY")) { // Don't log the API key
                        values.forEach(value -> log.debug("{}={}", name, value));
                    } else {
                        log.debug("{}=****", name);
                    }
                });
            }
            return Mono.just(clientRequest);
        });
    }

    // Logging filter for responses
    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            if (log.isDebugEnabled()) {
                log.debug("Response status: {}", clientResponse.statusCode());
            }
            return Mono.just(clientResponse);
        });
    }
}