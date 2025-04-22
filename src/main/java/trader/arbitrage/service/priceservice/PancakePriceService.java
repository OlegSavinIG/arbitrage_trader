package trader.arbitrage.service.priceservice;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.client.PancakeOnChainClient;
import trader.arbitrage.model.TokenPrice;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PancakePriceService {
    private final PancakeOnChainClient client;

    public Flux<TokenPrice> getPriceStream(String symbol) {
        return client.getPriceStream(symbol);
    }

    public Map<String, TokenPrice> getAllLatestPrices() {
        return client.getAllLatestPrices();
    }
}
