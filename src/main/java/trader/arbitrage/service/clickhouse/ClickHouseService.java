package trader.arbitrage.service.clickhouse;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import trader.arbitrage.database.ClickHouseRepository;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.model.clickhouse.ArbitrageEventRecord;
import trader.arbitrage.model.clickhouse.TokenPriceRecord;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

@Service
@Slf4j
@RequiredArgsConstructor
public class ClickHouseService {
    private final ClickHouseRepository repository;
    private final Executor jdbcExecutor;

    @Value("${clickhouse.batch-size:1000}")
    private int priceBatchSize;
    @Value("${clickhouse.batch-size:10}")
    private int eventBatchSize;

    private final Queue<TokenPriceRecord> priceBuffer = new ConcurrentLinkedQueue<>();
    private final Scheduler bufferScheduler = Schedulers.single();
    private final List<ArbitrageEventRecord> eventBuffer = Collections.synchronizedList(new ArrayList<>());

    @PostConstruct
    public void initBuffer() {
        Flux.interval(Duration.ofSeconds(1), bufferScheduler)
                .subscribe(v -> flushPricesAsync());
    }

    public Mono<Void> bufferPriceReactive(TokenPrice price) {
        return Mono.fromRunnable(() -> {
            priceBuffer.add(convertToPriceRecord(price));
            if (priceBuffer.size() >= priceBatchSize) {
                flushPricesAsync();
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Mono<Void> flushPricesAsync() {
        return Mono.fromCallable(() -> {
                    List<TokenPriceRecord> toSave = new ArrayList<>(priceBuffer);
                    priceBuffer.clear();
                    repository.savePricesBatch(toSave);
                    return toSave;
                })
                .subscribeOn(Schedulers.fromExecutor(jdbcExecutor))
                .doOnNext(saved -> log.info("Saved {} prices", saved.size()))
                .then();
    }



    public Flux<TokenPriceRecord> getPricesReactive(String symbol, LocalDateTime from, LocalDateTime to) {
        return Mono.fromCallable(() -> repository.findPrices(symbol, from, to))
                .subscribeOn(Schedulers.fromExecutor(jdbcExecutor))
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<TokenPriceRecord> getLatestPriceReactive(String symbol) {
        return Mono.fromCallable(() -> repository.findLatestPrice(symbol))
                .subscribeOn(Schedulers.fromExecutor(jdbcExecutor))
                .flatMap(optional -> optional.map(Mono::just).orElse(Mono.empty()));
    }

    public Mono<BigDecimal> getAveragePriceReactive(String symbol, LocalDateTime from, LocalDateTime to) {
        return Mono.fromCallable(() -> repository.findAveragePrice(symbol, from, to))
                .subscribeOn(Schedulers.fromExecutor(jdbcExecutor))
                .flatMap(bigDecimal -> bigDecimal.map(Mono::just).orElse(Mono.empty()));
    }

    private TokenPriceRecord convertToPriceRecord(TokenPrice price) {
        return new TokenPriceRecord(
                price.getSymbol(),
                price.getExchange(),
                price.getPrice(),
                price.getTimestamp().atZone(ZoneId.systemDefault()).toLocalDateTime()
        );
    }
//    public void bufferEvent(ArbitrageOpportunity opp) {
//        log.info("Buffered event {}, size - {}", opp.getSymbol(), eventBuffer.size());
//        eventBuffer.add(new ArbitrageEventRecord(
//                opp.getSymbol(),
//                "MEXC",
//                opp.getSecondExchangeName(),
//                opp.getMexcPrice(),
//                opp.getSecondExchangePrice(),
//                opp.getPriceDifferencePercent(),
//                opp.getTimestamp()
//        ));
//        if (eventBuffer.size() >= eventBatchSize) {
//            flushEvents();
//        }
//    }
//    public synchronized void flushEvents() {
//        if (eventBuffer.isEmpty()) return;
//        List<ArbitrageEventRecord> toSave = new ArrayList<>(eventBuffer);
//        eventBuffer.clear();
//        repository.saveEventsBatch(toSave);
//        log.info("Flushed {} arbitrage events to ClickHouse", toSave.size());
//    }
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        // на случай, если были данные до старта
        flushPricesAsync();
//        flushEvents();
    }

    @PreDestroy
    public void onDestroy() {
        flushPricesAsync();
//        flushEvents();
    }
}
