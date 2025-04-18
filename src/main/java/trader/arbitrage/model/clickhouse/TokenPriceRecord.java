package trader.arbitrage.model.clickhouse;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenPriceRecord {
    private String symbol;
    private String exchange;
    private BigDecimal price;
    private LocalDateTime timestamp;
}