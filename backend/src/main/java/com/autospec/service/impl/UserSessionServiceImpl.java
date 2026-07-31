package com.autospec.service.impl;

import com.autospec.entity.UserSession;
import com.autospec.mapper.UserSessionMapper;
import com.autospec.service.UserSessionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession>
        implements UserSessionService {
}
