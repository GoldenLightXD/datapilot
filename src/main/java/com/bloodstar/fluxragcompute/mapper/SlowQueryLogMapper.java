package com.bloodstar.fluxragcompute.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bloodstar.fluxragcompute.entity.SlowQueryLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SlowQueryLogMapper extends BaseMapper<SlowQueryLog> {
}
