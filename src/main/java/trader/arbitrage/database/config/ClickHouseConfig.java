package trader.arbitrage.database.config;

import com.clickhouse.jdbc.ClickHouseDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

@Configuration
@Slf4j
public class ClickHouseConfig {
    @Bean
    public DataSource clickHouseDataSource(
            @Value("${clickhouse.url}") String url,
            @Value("${clickhouse.user}") String user,
            @Value("${clickhouse.password}") String pass) {
        ClickHouseDataSource ds = null;
        try {
            ds = new ClickHouseDataSource(url, new Properties() {{
                put("user", user);
                put("password", pass);
            }});
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return ds;
    }

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate(DataSource clickHouseDataSource) {
        return new JdbcTemplate(clickHouseDataSource);
    }
}

