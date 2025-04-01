package trader.arbitrage.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ArbitrageService {
    private final MexcWebSocketService mexcWebSocketService;
    private final CoincapPriceService coincapPriceService;
    private final List<String> tokens;
    private final Map<String, TokenPrice> latestMexcPrices = new HashMap<>();
    private final Map<String, TokenPrice> latestCoincapPrices = new HashMap<>();

    @Value("${arbitrage.threshold:0.01}")
    private BigDecimal arbitrageThreshold;

    @Value("${arbitrage.check-interval:10}")
    private int checkInterval;

    @PostConstruct
    public void init() {
        tokens.forEach(this::startMonitoring);
        log.info("Initialized Arbitrage service with threshold: {}%, check interval: {}s",
                arbitrageThreshold.multiply(new BigDecimal("100")), checkInterval);

        // Периодически проверяем арбитражные возможности
        Flux.interval(Duration.ofSeconds(checkInterval))
                .doOnNext(i -> checkArbitrageOpportunities())
                .subscribe();
    }

    private void startMonitoring(String token) {
        // Подписываемся на обновления цен MEXC
        mexcWebSocketService.getPriceStream(token)
                .doOnNext(price -> {
                    latestMexcPrices.put(token, price);
                    log.debug("Updated MEXC price for {}: {}", token, price.getPrice());
                })
                .subscribe();

        // Подписываемся на обновления цен CoinCap
        coincapPriceService.getPriceStream(token)
                .doOnNext(price -> {
                    latestCoincapPrices.put(token, price);
                    log.debug("Updated CoinCap price for {}: {}", token, price.getPrice());
                })
                .subscribe();
    }

    private void checkArbitrageOpportunities() {
        for (String token : tokens) {
            TokenPrice mexcPrice = latestMexcPrices.get(token);
            TokenPrice coincapPrice = latestCoincapPrices.get(token);

            if (mexcPrice != null && coincapPrice != null) {
                calculateArbitrage(mexcPrice, coincapPrice);
            }
        }
    }

    private void calculateArbitrage(TokenPrice mexcPrice, TokenPrice coincapPrice) {
        String symbol = mexcPrice.getSymbol();
        BigDecimal mexcValue = mexcPrice.getPrice();
        BigDecimal coincapValue = coincapPrice.getPrice();

        // Вычисляем разницу в процентах
        BigDecimal priceDiff;
        if (mexcValue.compareTo(coincapValue) > 0) {
            priceDiff = mexcValue.subtract(coincapValue)
                    .divide(coincapValue, 4, RoundingMode.HALF_UP);
        } else {
            priceDiff = coincapValue.subtract(mexcValue)
                    .divide(mexcValue, 4, RoundingMode.HALF_UP);
        }

        // Если разница больше порогового значения
        if (priceDiff.compareTo(arbitrageThreshold) > 0) {
            ArbitrageOpportunity opportunity = ArbitrageOpportunity.builder()
                    .symbol(symbol)
                    .mexcPrice(mexcValue)
                    .coincapPrice(coincapValue)
                    .priceDifferencePercent(priceDiff.multiply(new BigDecimal("100")))
                    .timestamp(LocalDateTime.now())
                    .build();

            log.info("ARBITRAGE OPPORTUNITY: {}", opportunity);
        }
    }
}