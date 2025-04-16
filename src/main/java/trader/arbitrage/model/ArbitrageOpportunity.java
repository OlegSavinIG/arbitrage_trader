package trader.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageOpportunity {
    private String symbol;
    private BigDecimal mexcPrice;
    private BigDecimal secondExchangePrice;
    private BigDecimal priceDifferencePercent;
    private String secondExchangeName;
    private LocalDateTime timestamp;
}