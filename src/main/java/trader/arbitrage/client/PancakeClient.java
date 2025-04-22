package trader.arbitrage.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import trader.arbitrage.client.contracts.PancakeRouter02;
import trader.arbitrage.config.PancakeProperties;
import trader.arbitrage.model.TokenPrice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PancakeClient {
    private final Web3j web3j;
    private final TransactionManager txManager;
    private final ContractGasProvider gasProvider;
    private final PancakeProperties props;

    private PancakeRouter02 router;
    private final Map<String, Sinks.Many<TokenPrice>> streams = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        router = PancakeRouter02.load(
                props.getRouterAddress(),
                web3j,
                txManager,
                gasProvider
        );
        props.getTokens().forEach((sym, addr) ->
                streams.put(sym + "_USDT", Sinks.many().multicast().onBackpressureBuffer())
        );
        log.info("PancakeClient initialized for tokens: {}", props.getTokens().keySet());
        log.info("Streams {} ", streams);
    }

    @Scheduled(fixedRateString = "${pancake.update-interval}")
    public void fetchPrices() {
        streams.forEach((symbol, sink) -> {
            try {
                String tokenSym = symbol.replace("_USDT", "");
                String tokenAddr = props.getTokens().get(tokenSym);

                if (tokenAddr == null || tokenAddr.isEmpty()) {
                    log.error("Invalid token address for symbol: {}", tokenSym);
                    return;
                }

                BigDecimal price = fetchPrice(tokenAddr);

                if (price == null) {
                    log.error("Price for token {} could not be fetched", tokenSym);
                    return;
                }

                TokenPrice tp = TokenPrice.builder()
                        .symbol(symbol)
                        .price(price)
                        .exchange("Pancake")
                        .timestamp(Instant.now())
                        .build();
                sink.tryEmitNext(tp);
                log.debug("Pancake price [{}]: {}", symbol, price);
            } catch (Exception e) {
                log.error("Error fetching price for {}: {}", symbol, e.getMessage(), e);
            }
        });
    }

    public BigDecimal fetchPrice(String tokenAddress) throws Exception {
        try {
            List<String> path = List.of(
                    tokenAddress,
                    props.getWrappedBnb(),
                    props.getBusd()
            );

            log.debug("Fetching price for path: {}", path);

            if (path.stream().anyMatch(e -> e == null || e.isEmpty())) {
                log.error("Invalid path for price fetch: {}", path);
                throw new IllegalArgumentException("Invalid token path provided.");
            }

            List<BigInteger> amountsOut = router.getAmountsOut(
                    BigInteger.TEN.pow(18), // 1 токен в 18 знаках
                    path
            ).send();

            if (amountsOut == null || amountsOut.isEmpty()) {
                log.error("Received empty amountsOut for token address: {}", tokenAddress);
                throw new IllegalStateException("No amounts returned from Pancake router.");
            }

            BigInteger amountOut = amountsOut.get(amountsOut.size() - 1); // последний — цена в BUSD

            // Дополнительная проверка на отрицательные значения или ошибки
            if (amountOut.compareTo(BigInteger.ZERO) <= 0) {
                log.error("Invalid price amount for token address {}: {}", tokenAddress, amountOut);
                throw new IllegalStateException("Received invalid price amount.");
            }

            BigDecimal price = new BigDecimal(amountOut)
                    .divide(BigDecimal.TEN.pow(18), 8, RoundingMode.HALF_UP);

            log.debug("Fetched price for token {}: {}", tokenAddress, price);
            return price;
        } catch (Exception e) {
            log.error("Error fetching price for token address {}: {}", tokenAddress, e.getMessage(), e);
            throw e;  // Перекидываем исключение дальше, чтобы оно было обработано в вызывающем коде
        }
    }
    public Map<String, Sinks.Many<TokenPrice>> getStreams() {
        return Collections.unmodifiableMap(streams);
    }


    public Flux<TokenPrice> getPriceStream(String symbol) {
        Sinks.Many<TokenPrice> sink = streams.get(symbol);
        return sink != null ? sink.asFlux() : Flux.empty();
    }
}

