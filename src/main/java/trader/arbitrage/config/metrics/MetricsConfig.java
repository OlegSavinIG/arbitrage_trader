package trader.arbitrage.config.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import trader.arbitrage.service.arbitrage.metricscounter.ArbitrageOpportunityProvider;

import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class MetricsConfig {

    private final AtomicInteger arbitrageOpportunitiesCount = new AtomicInteger(0);

    @Bean
    public Counter arbitrageOpportunityCounter(MeterRegistry registry) {
        return Counter.builder("arbitrage.opportunities.detected")
                .description("Number of arbitrage opportunities detected")
                .register(registry);
    }

    @Bean
    public Gauge arbitrageOpportunitiesGauge(MeterRegistry registry, ArbitrageOpportunityProvider arbitrageService) {
        return Gauge.builder("arbitrage.opportunities.active",
                        () -> arbitrageService.getAllArbitrageOpportunities().size())
                .description("Current number of active arbitrage opportunities")
                .register(registry);
    }

    @Bean
    public Counter apiCallsCounter(MeterRegistry registry) {
        return Counter.builder("api.calls.total")
                .description("Number of API calls made")
                .register(registry);
    }

    @Bean
    public Counter telegramNotificationsCounter(MeterRegistry registry) {
        return Counter.builder("telegram.notifications.sent")
                .description("Number of Telegram notifications sent")
                .register(registry);
    }
}
