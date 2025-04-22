package trader.arbitrage.config.webclient;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.ReadonlyTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import trader.arbitrage.config.PancakeProperties;

@Configuration
@RequiredArgsConstructor
public class PancakeWeb3jConfig {
    private final PancakeProperties props;

    @Bean
    public Web3j web3j(@Value("${bsc.rpc-url}") String rpcUrl) {
        return Web3j.build(new HttpService(rpcUrl));
    }

    @Bean
    public TransactionManager txManager(Web3j web3j) {
        // Используем ReadonlyTransactionManager для eth_call без приватного ключа
        return new ReadonlyTransactionManager(web3j, props.getRouterAddress());
    }

    @Bean
    public ContractGasProvider gasProvider() {
        return new DefaultGasProvider();
    }
}
