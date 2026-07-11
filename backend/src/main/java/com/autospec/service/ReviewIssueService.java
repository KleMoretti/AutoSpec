package com.autospec.service;

import com.autospec.entity.ReviewIssue;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ReviewIssueService extends IService<ReviewIssue> {

    List<ReviewIssue> listByProjectId(Long projectId, int limit, int offset);
}
