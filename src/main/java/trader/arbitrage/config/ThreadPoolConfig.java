package trader.arbitrage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class ThreadPoolConfig {

    @Bean(name = "jdbcExecutor")
    public Executor jdbcExecutor() {
        return Executors.newCachedThreadPool();
    }
}