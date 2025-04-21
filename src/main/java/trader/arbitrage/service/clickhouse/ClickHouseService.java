package trader.arbitrage.service.clickhouse;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import trader.arbitrage.database.ClickHouseRepository;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.model.clickhouse.ArbitrageEventRecord;
import trader.arbitrage.model.clickhouse.TokenPriceRecord;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Сервис для буферизации и пакетной записи данных в ClickHouse.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ClickHouseService {
    private final ClickHouseRepository repository;

    @Value("${clickhouse.batch-size:1000}")
    private int priceBatchSize;
    @Value("${clickhouse.batch-size:10}")
    private int eventBatchSize;

    // Буферы
    private final List<TokenPriceRecord> priceBuffer = Collections.synchronizedList(new ArrayList<>());
    private final List<ArbitrageEventRecord> eventBuffer = Collections.synchronizedList(new ArrayList<>());

    /**
     * Буферизует запись цены для последующей пакетной вставки.
     */
    public void bufferPrice(TokenPrice price) {
        log.info("Buffered price {}, size - {}", price.getSymbol(), priceBuffer.size());
        priceBuffer.add(new TokenPriceRecord(
                price.getSymbol(),
                price.getExchange(),
                price.getPrice(),
                price.getTimestamp().atZone(ZoneId.systemDefault()).toLocalDateTime()
        ));
        if (priceBuffer.size() >= priceBatchSize) {
            flushPrices();
        }
    }

    /**
     * Буферизует арбитражное событие.
     */
    public void bufferEvent(ArbitrageOpportunity opp) {
        log.info("Buffered event {}, size - {}", opp.getSymbol(), eventBuffer.size());
        eventBuffer.add(new ArbitrageEventRecord(
                opp.getSymbol(),
                "MEXC",
                opp.getSecondExchangeName(),
                opp.getMexcPrice(),
                opp.getSecondExchangePrice(),
                opp.getPriceDifferencePercent(),
                opp.getTimestamp()
        ));
        if (eventBuffer.size() >= eventBatchSize) {
            flushEvents();
        }
    }

    /**
     * Синхронный сброс буфера цен.
     */
    public synchronized void flushPrices() {
        if (priceBuffer.isEmpty()) return;
        List<TokenPriceRecord> toSave = new ArrayList<>(priceBuffer);
        priceBuffer.clear();
        repository.savePricesBatch(toSave);
        log.info("Flushed {} price records to ClickHouse", toSave.size());
    }

    /**
     * Синхронный сброс буфера событий.
     */
    public synchronized void flushEvents() {
        if (eventBuffer.isEmpty()) return;
        List<ArbitrageEventRecord> toSave = new ArrayList<>(eventBuffer);
        eventBuffer.clear();
        repository.saveEventsBatch(toSave);
        log.info("Flushed {} arbitrage events to ClickHouse", toSave.size());
    }

    /**
     * Сбрасываем остаток при старте приложения, чтобы гарантировать чистоту буферов.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReady() {
        // на случай, если были данные до старта
        flushPrices();
        flushEvents();
    }

    /**
     * Сбрасываем остаток перед завершением.
     */
    @PreDestroy
    public void onDestroy() {
        flushPrices();
        flushEvents();
    }
}
