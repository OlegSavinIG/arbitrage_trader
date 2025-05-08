package trader.arbitrage.database;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import trader.arbitrage.model.clickhouse.ArbitrageEventRecord;
import trader.arbitrage.model.clickhouse.TokenPriceRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
            "INSERT INTO token_prices " +
                    "(symbol, exchange, price, timestamp) VALUES (?, ?, ?, ?) " +
                    "SETTINGS input_format_allow_errors_ratio = 0.1";

    private static final String EVENT_SQL =
            "INSERT INTO arbitrage_events " +
                    "(symbol, primary_exchange, secondary_exchange, primary_price, " +
                    "secondary_price, diff_percent, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                    "SETTINGS input_format_allow_errors_num = 50";
    private static final String SELECT_PRICES_SQL =
            "SELECT symbol, exchange, price, timestamp " +
                    "FROM token_prices " +
                    "WHERE symbol = ? AND timestamp BETWEEN ? AND ? " +
                    "ORDER BY timestamp";
    private static final String SELECT_LATEST_PRICE_SQL =
            "SELECT symbol, exchange, price, timestamp " +
                    "FROM token_prices " +
                    "WHERE symbol = ? " +
                    "ORDER BY timestamp DESC " +
                    "LIMIT 1";
    private static final String SELECT_AVG_PRICE_SQL =
            "SELECT AVG(price) AS avg_price " +
                    "FROM token_prices " +
                    "WHERE symbol = ? AND timestamp BETWEEN ? AND ?";
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

    /**
     * Получение цен по диапазону времени.
     */
    public List<TokenPriceRecord> findPrices(String symbol, LocalDateTime from, LocalDateTime to) {
        return clickHouseJdbcTemplate.query(
                SELECT_PRICES_SQL,
                new BeanPropertyRowMapper<>(TokenPriceRecord.class),
                symbol, from, to
        );
    }

    /**
     * Получение последней цены для символа.
     */
    public Optional<TokenPriceRecord> findLatestPrice(String symbol) {
        List<TokenPriceRecord> list = clickHouseJdbcTemplate.query(
                SELECT_LATEST_PRICE_SQL,
                new BeanPropertyRowMapper<>(TokenPriceRecord.class),
                symbol
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * Получение средней цены за период.
     */
    public Optional<BigDecimal> findAveragePrice(String symbol, LocalDateTime from, LocalDateTime to) {
        BigDecimal avg = clickHouseJdbcTemplate.queryForObject(
                SELECT_AVG_PRICE_SQL,
                BigDecimal.class,
                symbol, from, to
        );
        return Optional.ofNullable(avg);
    }
}


