package com.autospec.service.impl;

import com.autospec.entity.KnowledgeDocument;
import com.autospec.mapper.KnowledgeDocumentMapper;
import com.autospec.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {
}
