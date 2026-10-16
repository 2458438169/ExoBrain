package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.kama.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.kama.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.kama.jchatmind.model.response.GetKnowledgeBasesResponse;
import com.kama.jchatmind.service.KnowledgeBaseFacadeService;
import com.kama.jchatmind.auth.ResourceAccess;
import com.kama.jchatmind.model.vo.KnowledgeBaseVO;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseFacadeService knowledgeBaseFacadeService;
    private final ResourceAccess access;

    // 查询所有知识库
    @GetMapping("/knowledge-bases")
    public ApiResponse<GetKnowledgeBasesResponse> getKnowledgeBases() {
        var response = knowledgeBaseFacadeService.getKnowledgeBases();
        response.setKnowledgeBases(Arrays.stream(response.getKnowledgeBases())
                .filter(kb -> access.canRead(ResourceAccess.KB, kb.getId()))
                .peek(kb -> {
                    kb.setReadOnly(!access.canWrite(ResourceAccess.KB, kb.getId()));
                    kb.setPublicResource(access.isPublicKnowledgeBase(kb.getId()));
                })
                .toArray(KnowledgeBaseVO[]::new));
        return ApiResponse.success(response);
    }

    // 创建知识库
    @PostMapping("/knowledge-bases")
    @Transactional
    public ApiResponse<CreateKnowledgeBaseResponse> createKnowledgeBase(@RequestBody CreateKnowledgeBaseRequest request) {
        ResourceAccess.text(request.getName(), "知识库名称", 100, true);
        ResourceAccess.text(request.getDescription(), "知识库简介", 2000, false);
        var response = knowledgeBaseFacadeService.createKnowledgeBase(request);
        access.claim(ResourceAccess.KB, response.getKnowledgeBaseId());
        return ApiResponse.success(response);
    }

    // 删除知识库
    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    @Transactional
    public ApiResponse<Void> deleteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        access.requireKb(knowledgeBaseId, true);
        knowledgeBaseFacadeService.deleteKnowledgeBase(knowledgeBaseId);
        access.remove(ResourceAccess.KB, knowledgeBaseId);
        return ApiResponse.success();
    }

    // 更新知识库
    @PatchMapping("/knowledge-bases/{knowledgeBaseId}")
    public ApiResponse<Void> updateKnowledgeBase(@PathVariable String knowledgeBaseId, @RequestBody UpdateKnowledgeBaseRequest request) {
        access.requireKb(knowledgeBaseId, true);
        if (request.getName() != null) ResourceAccess.text(request.getName(), "知识库名称", 100, true);
        ResourceAccess.text(request.getDescription(), "知识库简介", 2000, false);
        knowledgeBaseFacadeService.updateKnowledgeBase(knowledgeBaseId, request);
        return ApiResponse.success();
    }
}
