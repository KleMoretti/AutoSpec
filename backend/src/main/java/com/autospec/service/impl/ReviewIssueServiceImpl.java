package com.autospec.service.impl;

import com.autospec.entity.ReviewIssue;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.service.ReviewIssueService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ReviewIssueServiceImpl extends ServiceImpl<ReviewIssueMapper, ReviewIssue> implements ReviewIssueService {
}
