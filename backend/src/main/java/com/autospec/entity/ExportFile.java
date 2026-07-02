package com.autospec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("export_file")
public class ExportFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long jobId;

    private String fileName;

    private String mediaType;

    private String encoding;

    private String content;

    private LocalDateTime createdAt;
}
