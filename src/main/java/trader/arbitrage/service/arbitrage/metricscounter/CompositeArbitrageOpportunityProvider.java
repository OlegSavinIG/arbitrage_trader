package trader.arbitrage.service.arbitrage.metricscounter;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import trader.arbitrage.model.ArbitrageOpportunity;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Primary
public class CompositeArbitrageOpportunityProvider implements ArbitrageOpportunityProvider {
    private final List<ArbitrageOpportunityProvider> providers;

    public CompositeArbitrageOpportunityProvider(List<ArbitrageOpportunityProvider> providers) {
        this.providers = providers;
    }

    @Override
    public List<ArbitrageOpportunity> getAllArbitrageOpportunities() {
        return providers.stream()
                .flatMap(p -> p.getAllArbitrageOpportunities().stream())
                .collect(Collectors.toList());
    }
}