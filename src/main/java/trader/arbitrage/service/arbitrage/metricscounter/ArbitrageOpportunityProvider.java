package trader.arbitrage.service.arbitrage.metricscounter;

import trader.arbitrage.model.ArbitrageOpportunity;

import java.util.List;

public interface ArbitrageOpportunityProvider {
    List<ArbitrageOpportunity> getAllArbitrageOpportunities();
}