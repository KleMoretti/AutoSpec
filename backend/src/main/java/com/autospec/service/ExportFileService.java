package com.autospec.service;

import com.autospec.entity.ExportFile;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ExportFileService extends IService<ExportFile> {

    List<ExportFile> listByProjectId(Long projectId, int limit, int offset);

    ExportFile getByProjectIdAndId(Long projectId, Long exportFileId);
}
