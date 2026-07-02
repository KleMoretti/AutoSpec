package com.autospec.service.impl;

import com.autospec.entity.ProjectMember;
import com.autospec.mapper.ProjectMemberMapper;
import com.autospec.service.ProjectMemberService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ProjectMemberServiceImpl extends ServiceImpl<ProjectMemberMapper, ProjectMember> implements ProjectMemberService {
}
