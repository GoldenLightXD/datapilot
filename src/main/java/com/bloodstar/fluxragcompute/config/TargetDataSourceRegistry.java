package com.bloodstar.fluxragcompute.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bloodstar.fluxragcompute.entity.TargetDatasource;
import com.bloodstar.fluxragcompute.mapper.TargetDatasourceMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class TargetDataSourceRegistry {

    private final ConcurrentHashMap<String, JdbcTemplate> registry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final TargetDatasourceMapper mapper;

    @PostConstruct
    public void init() {
        List<TargetDatasource> list = mapper.selectList(
                new LambdaQueryWrapper<TargetDatasource>().eq(TargetDatasource::getStatus, 1));
        list.forEach(this::registerDataSource);
        log.info("Loaded {} target datasource(s): {}", registry.size(), registry.keySet());
    }

    public void addDataSource(TargetDatasource config) {
        removeDataSource(config.getInstanceId());
        registerDataSource(config);
        log.info("Added target datasource: {}", config.getInstanceId());
    }

    public void removeDataSource(String instanceId) {
        registry.remove(instanceId);
        HikariDataSource ds = dataSources.remove(instanceId);
        if (ds != null) {
            ds.close();
            log.info("Removed target datasource: {}", instanceId);
        }
    }

    public JdbcTemplate getJdbcTemplate(String instanceId) {
        JdbcTemplate jt = registry.get(instanceId);
        if (jt == null) {
            throw new IllegalArgumentException(
                    "未知的数据库实例: " + instanceId + ", 可用实例: " + registry.keySet());
        }
        return jt;
    }

    public Set<String> getAvailableInstanceIds() {
        return Set.copyOf(registry.keySet());
    }

    private void registerDataSource(TargetDatasource config) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(config.getUrl());
        ds.setUsername(config.getUsername());
        ds.setPassword(config.getPassword());
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(5000);
        ds.setReadOnly(true);
        ds.setPoolName("target-" + config.getInstanceId());
        dataSources.put(config.getInstanceId(), ds);
        registry.put(config.getInstanceId(), new JdbcTemplate(ds));
    }
}
