package com.autospec.service.impl;

import com.autospec.entity.AgentTask;
import com.autospec.mapper.AgentTaskMapper;
import com.autospec.service.AgentTaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskServiceImpl extends ServiceImpl<AgentTaskMapper, AgentTask> implements AgentTaskService {
}
