package trader.arbitrage.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "dexscreener")
public class DexscreenerProperties {
    private String baseUrl;
    private Map<String, List<Token>> tokens;

    @Data
    public static class Token {
        private String symbol;
        private String address;
    }
}

