package com.autospec.mapper;

import com.autospec.entity.AuditEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditEventMapper extends BaseMapper<AuditEvent> {
}
