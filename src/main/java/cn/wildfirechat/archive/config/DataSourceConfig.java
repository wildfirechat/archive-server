package cn.wildfirechat.archive.config;

import com.clickhouse.jdbc.DataSourceImpl;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Properties;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class DataSourceConfig {
    
    private final ArchiveProperties archiveProperties;
    
    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(archiveProperties.getMongodb().getUri());
    }
    
    /**
     * MySQL 主数据源
     */
    @Bean
    @Primary
    public DataSource dataSource() {
        ArchiveProperties.MysqlConfig mysqlConfig = archiveProperties.getMysql();
        
        HikariConfig config = new HikariConfig();
        
        // 构建 JDBC URL，确保使用 UTC 时区（与消息源时区一致）
        String jdbcUrl = mysqlConfig.getUrl();
        if (!jdbcUrl.contains("serverTimezone=")) {
            jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "serverTimezone=UTC";
        }
        config.setJdbcUrl(jdbcUrl);
        
        config.setUsername(mysqlConfig.getUsername());
        config.setPassword(mysqlConfig.getPassword());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        
        // 连接池配置
        config.setMinimumIdle(5);
        config.setMaximumPoolSize(20);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        return new HikariDataSource(config);
    }
    
    /**
     * MySQL JdbcTemplate（主）
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
    
    /**
     * ClickHouse 数据源
     */
    @Bean(name = "clickhouseDataSource")
    public DataSource clickhouseDataSource() throws SQLException {
        ArchiveProperties.ClickhouseConfig config = archiveProperties.getClickhouse();
        
        // 转换为 HTTP 协议格式
        String url = config.getUrl();
        if (url.startsWith("jdbc:clickhouse://")) {
            url = url.replace("jdbc:clickhouse://", "jdbc:ch:http://");
        }
        
        // 设置连接属性
        Properties properties = new Properties();
        properties.setProperty("user", config.getUsername());
        properties.setProperty("password", config.getPassword());
        
        log.info("ClickHouse URL: {}", url);
        
        return new DataSourceImpl(url, properties);
    }
    
    /**
     * ClickHouse JdbcTemplate
     */
    @Bean(name = "clickhouseJdbcTemplate")
    public JdbcTemplate clickhouseJdbcTemplate(@Qualifier("clickhouseDataSource") DataSource clickhouseDataSource) {
        return new JdbcTemplate(clickhouseDataSource);
    }
}
