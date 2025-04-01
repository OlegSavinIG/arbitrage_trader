package trader.arbitrage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class TokenConfig {
    @Bean
    public List<String> tokens() {
        // Список токенов для отслеживания
        return List.of("BTC", "ETH", "BNB", "SOL", "XRP");
    }
}