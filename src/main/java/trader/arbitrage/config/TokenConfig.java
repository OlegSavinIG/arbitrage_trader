package trader.arbitrage.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class TokenConfig {

    @Value("${trading.tokens}")
    private String tokensString;

    @Bean
    public List<String> tradingTokens() {
        return Arrays.asList(tokensString.split(","));
    }
}