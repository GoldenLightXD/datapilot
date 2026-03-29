package com.bloodstar.fluxragcompute.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bloodstar.fluxragcompute.common.BaseResponse;
import com.bloodstar.fluxragcompute.common.ResultUtils;
import com.bloodstar.fluxragcompute.config.TargetDataSourceRegistry;
import com.bloodstar.fluxragcompute.dto.DataSourceRequest;
import com.bloodstar.fluxragcompute.entity.TargetDatasource;
import com.bloodstar.fluxragcompute.mapper.TargetDatasourceMapper;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/datasources")
@RequiredArgsConstructor
public class DataSourceController {

    private final TargetDatasourceMapper mapper;
    private final TargetDataSourceRegistry registry;

    @PostMapping
    public BaseResponse<TargetDatasource> add(@Valid @RequestBody DataSourceRequest request) {
        TargetDatasource entity = new TargetDatasource();
        entity.setInstanceId(request.getInstanceId());
        entity.setName(request.getName());
        entity.setUrl(request.getUrl());
        entity.setUsername(request.getUsername());
        entity.setPassword(request.getPassword());
        entity.setStatus(1);
        mapper.insert(entity);
        registry.addDataSource(entity);
        return ResultUtils.success(entity);
    }

    @DeleteMapping("/{instanceId}")
    public BaseResponse<Void> remove(@PathVariable String instanceId) {
        mapper.delete(new LambdaQueryWrapper<TargetDatasource>()
                .eq(TargetDatasource::getInstanceId, instanceId));
        registry.removeDataSource(instanceId);
        return ResultUtils.success(null);
    }

    @GetMapping
    public BaseResponse<List<TargetDatasource>> list() {
        List<TargetDatasource> list = mapper.selectList(null);
        // 脱敏：不返回密码
        list.forEach(ds -> ds.setPassword("******"));
        return ResultUtils.success(list);
    }

    @PostMapping("/{instanceId}/test")
    public BaseResponse<String> testConnection(@PathVariable String instanceId) {
        try {
            registry.getJdbcTemplate(instanceId).queryForObject("SELECT 1", Integer.class);
            return ResultUtils.success("连接成功");
        } catch (Exception e) {
            return ResultUtils.success("连接失败: " + e.getMessage());
        }
    }
}
