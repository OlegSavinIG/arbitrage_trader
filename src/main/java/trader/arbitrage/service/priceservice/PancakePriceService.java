package trader.arbitrage.service.priceservice;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.client.PancakeClient;
import trader.arbitrage.config.PancakeProperties;
import trader.arbitrage.model.TokenPrice;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class PancakePriceService {
    private final PancakeClient client;
    private final Map<String, TokenPrice> latestPrices = new ConcurrentHashMap<>();
    private final PancakeProperties props;

    @PostConstruct
    public void init() {
        log.info("Initializing subscriptions to Pancake price streams...");

        props.getTokens().keySet().forEach(symbol -> {
            String fullSymbol = symbol + "_USDT";

            log.info("Subscribing to price stream for: {}", fullSymbol);

            client.getPriceStream(fullSymbol)
                    .subscribe(tp -> {
                        latestPrices.put(fullSymbol, tp);
                        log.debug("Received price update for {}: {}", fullSymbol, tp.getPrice());
                    }, error -> {
                        log.error("Error in stream for {}: {}", fullSymbol, error.getMessage(), error);
                    });

        });

        log.info("All subscriptions initialized.");
    }

    public Flux<TokenPrice> getPriceStream(String symbol) {
        return client.getPriceStream(symbol);
    }
    public Map<String, TokenPrice> getLatestSnapshot() {
        return new HashMap<>(latestPrices);
    }
}
