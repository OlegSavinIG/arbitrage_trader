package trader.arbitrage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfiguration {
    @Bean
    public WebClient coinCapClient(@Value("${coincap.api.url}") String baseUrl,
                                   @Value("${coincap.api.key}") String apiKey
    ) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-CMC_PRO_API_KEY", apiKey)
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                                .responseTimeout(Duration.ofMillis(3000))
                ))
                .build();
    }
}
