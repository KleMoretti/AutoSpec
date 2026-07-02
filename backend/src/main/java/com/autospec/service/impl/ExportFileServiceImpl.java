package com.autospec.service.impl;

import com.autospec.entity.ExportFile;
import com.autospec.mapper.ExportFileMapper;
import com.autospec.service.ExportFileService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ExportFileServiceImpl extends ServiceImpl<ExportFileMapper, ExportFile> implements ExportFileService {
}
