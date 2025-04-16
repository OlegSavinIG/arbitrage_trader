package trader.arbitrage.config.metrics;


import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention;

@Configuration
@RequiredArgsConstructor
public class WebClientTracingConfig {

    private final ObservationRegistry observationRegistry;

    /**
     * Настраивает все экземпляры WebClient для включения инструментов трассировки.
     */
    @Bean
    public WebClientCustomizer webClientCustomizer() {
        return webClientBuilder -> webClientBuilder
                .observationRegistry(observationRegistry)
                .observationConvention(new DefaultClientRequestObservationConvention());
    }
}
