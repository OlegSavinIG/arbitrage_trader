package trader.arbitrage.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "pancake")
public class PancakeProperties {
    private String routerAddress;
    private String wrappedBnb;
    private String busd;
    private long updateInterval = 5000; // по умолчанию 30 сек
    private Map<String, String> tokens;      // symbol -> tokenAddress
}
