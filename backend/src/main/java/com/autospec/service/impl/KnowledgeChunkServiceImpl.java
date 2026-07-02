package com.autospec.service.impl;

import com.autospec.entity.KnowledgeChunk;
import com.autospec.mapper.KnowledgeChunkMapper;
import com.autospec.service.KnowledgeChunkService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeChunkServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk> implements KnowledgeChunkService {
}
