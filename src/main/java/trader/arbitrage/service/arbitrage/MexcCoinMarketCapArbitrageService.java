package trader.arbitrage.service.arbitrage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.priceservice.CoinMarketCapService;
import trader.arbitrage.service.priceservice.MexcPriceService;
import trader.arbitrage.telegram.TelegramNotificationService;

import java.util.Map;

@Slf4j
@Service
public class MexcCoinMarketCapArbitrageService extends BaseArbitrageService {

    private static final String PRIMARY_EXCHANGE = "MEXC";
    private static final String SECONDARY_EXCHANGE = "CoinMarketCap";

    private final MexcPriceService mexcPriceService;
    private final CoinMarketCapService coinMarketCapClient;

    public MexcCoinMarketCapArbitrageService(
            MexcPriceService mexcPriceService,
            CoinMarketCapService coinMarketCapClient,
            TelegramNotificationService telegramService,
            Counter arbitrageOpportunityCounter,
            Counter telegramNotificationsCounter) {
        super(telegramService, arbitrageOpportunityCounter, telegramNotificationsCounter);
        this.mexcPriceService = mexcPriceService;
        this.coinMarketCapClient = coinMarketCapClient;
    }

    /**
     * Scheduled method to check for arbitrage opportunities between exchanges
     */
    @Override
    @Scheduled(fixedRateString = "${arbitrage.check-interval}")
    @Observed(name = "MexcCoinCheckForArbitrageOpportunities",
            contextualName = "check-arbitrage-opportunities-mexccoin")
    public void checkForArbitrageOpportunities() {
        // Get all latest prices from both exchanges
        Map<String, TokenPrice> mexcPrices = mexcPriceService.getAllLatestPrices();
        Map<String, TokenPrice> coinmarketcapPrices = coinMarketCapClient.getAllLatestPrices();

        // Use template method from base class
        super.checkForArbitrageOpportunities(
                mexcPrices,
                coinmarketcapPrices,
                PRIMARY_EXCHANGE,
                SECONDARY_EXCHANGE);
    }
}