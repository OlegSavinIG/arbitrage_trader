package trader.arbitrage.service.arbitrage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.priceservice.MexcPriceService;
import trader.arbitrage.service.priceservice.PancakePriceService;
import trader.arbitrage.telegram.TelegramNotificationService;

import java.util.Map;

@Slf4j
@Service
public class MexcPancakeArbitrageService extends BaseArbitrageService {
    private static final String PRIMARY = "MEXC";
    private static final String SECONDARY = "PancakeSwap";

    private final MexcPriceService mexc;
    private final PancakePriceService pancake;

    public MexcPancakeArbitrageService(
            MexcPriceService mexc,
            PancakePriceService pancake,
            TelegramNotificationService telegramService,
            Counter arbitrageOpportunityCounter,
            Counter telegramNotificationsCounter) {
        super(telegramService, arbitrageOpportunityCounter, telegramNotificationsCounter);
        this.mexc = mexc;
        this.pancake = pancake;
    }

    @Override
    @Scheduled(fixedRateString = "${arbitrage.check-interval}")
    @Observed(name = "MexcPancakeCheck", contextualName = "arb-mexc-pancake")
    public void checkForArbitrageOpportunities() {
        Map<String, TokenPrice> mPrices = mexc.getAllLatestPrices();
        Map<String, TokenPrice> pPrices = pancake.getAllLatestPrices();
        super.checkForArbitrageOpportunities(mPrices, pPrices, PRIMARY, SECONDARY);
    }
}

