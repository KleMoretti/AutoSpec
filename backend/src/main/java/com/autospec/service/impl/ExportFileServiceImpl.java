package com.autospec.service.impl;

import com.autospec.entity.ExportFile;
import com.autospec.mapper.ExportFileMapper;
import com.autospec.service.ExportFileService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExportFileServiceImpl extends ServiceImpl<ExportFileMapper, ExportFile> implements ExportFileService {

    @Override
    public List<ExportFile> listByProjectId(Long projectId, int limit, int offset) {
        return lambdaQuery()
                .eq(ExportFile::getProjectId, projectId)
                .orderByAsc(ExportFile::getId)
                .last("limit " + limit + " offset " + offset)
                .list();
    }

    @Override
    public ExportFile getByProjectIdAndId(Long projectId, Long exportFileId) {
        return lambdaQuery()
                .eq(ExportFile::getProjectId, projectId)
                .eq(ExportFile::getId, exportFileId)
                .one();
    }
}
