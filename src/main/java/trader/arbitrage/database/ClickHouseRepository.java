package trader.arbitrage.database;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import trader.arbitrage.model.clickhouse.ArbitrageEventRecord;
import trader.arbitrage.model.clickhouse.TokenPriceRecord;

import java.util.List;
import java.util.Optional;

/**
 * Репозиторий для низкоуровневых операций с ClickHouse.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class ClickHouseRepository {
    private static final String PRICE_SQL =
            "INSERT INTO token_prices (symbol, exchange, price, timestamp) VALUES (?, ?, ?, ?)";
    private static final String EVENT_SQL =
            "INSERT INTO arbitrage_events (symbol, primary_exchange, secondary_exchange, primary_price, secondary_price, diff_percent, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate clickHouseJdbcTemplate;

    /**
     * Пакетная вставка цен токенов.
     */
    public void savePricesBatch(List<TokenPriceRecord> records) {
        log.info("Saving price batch {}", records.size());
        List<Object[]> args = records.stream()
                .map(r -> new Object[]{
                        r.getSymbol(),
                        Optional.ofNullable(r.getExchange()).orElse("UNKNOWN"),
                        r.getPrice(),
                        r.getTimestamp()
                })
                .toList();
        clickHouseJdbcTemplate.batchUpdate(PRICE_SQL, args);
    }

    /**
     * Пакетная вставка арбитражных событий.
     */
    public void saveEventsBatch(List<ArbitrageEventRecord> records) {
        log.info("Saving event batch {}", records.size());
        List<Object[]> args = records.stream()
                .map(r -> new Object[]{
                        r.getSymbol(),
                        r.getPrimaryExchange(),
                        r.getSecondaryExchange(),
                        r.getPrimaryPrice(),
                        r.getSecondaryPrice(),
                        r.getDiffPercent(),
                        r.getTimestamp()
                })
                .toList();
        clickHouseJdbcTemplate.batchUpdate(EVENT_SQL, args);
    }
}
