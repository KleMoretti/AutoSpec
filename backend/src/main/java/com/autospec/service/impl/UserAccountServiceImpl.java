package com.autospec.service.impl;

import com.autospec.entity.UserAccount;
import com.autospec.mapper.UserAccountMapper;
import com.autospec.service.UserAccountService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements UserAccountService {
}
