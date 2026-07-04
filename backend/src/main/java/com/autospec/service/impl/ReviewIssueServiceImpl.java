package com.autospec.service.impl;

import com.autospec.entity.ReviewIssue;
import com.autospec.mapper.ReviewIssueMapper;
import com.autospec.service.ReviewIssueService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReviewIssueServiceImpl extends ServiceImpl<ReviewIssueMapper, ReviewIssue> implements ReviewIssueService {

    @Override
    public List<ReviewIssue> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(ReviewIssue::getProjectId, projectId)
                .orderByAsc(ReviewIssue::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }
}
