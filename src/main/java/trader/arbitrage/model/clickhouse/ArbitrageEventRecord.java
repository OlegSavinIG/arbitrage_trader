package trader.arbitrage.model.clickhouse;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ArbitrageEventRecord {
    private String symbol;
    private String primaryExchange;
    private String secondaryExchange;
    private BigDecimal primaryPrice;
    private BigDecimal secondaryPrice;
    private BigDecimal diffPercent;
    private LocalDateTime timestamp;
}