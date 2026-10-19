package com.kama.jchatmind.demo;

import com.kama.jchatmind.exception.DocumentContentException;
import com.kama.jchatmind.exception.GlobalExceptionHandler;
import com.kama.jchatmind.ingestion.IngestionJobView;
import com.kama.jchatmind.ingestion.IngestionService;
import com.kama.jchatmind.service.DocumentFacadeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UX-01：游客能读公开文档的导入状态，私有/不存在的文档与游客重试路径都要被挡住。
 *
 * <p>这条路径只读：重试导入仍是账号能力，网关白名单里也没有 {@code /retry}。</p>
 */
class DemoIngestionRoutingTest {

    private static final String DOCUMENT = "77777777-7777-4777-8777-777777777777";

    @Test
    @DisplayName("公开文档的导入状态可读，并且只做只读查询")
    void publicDocumentStatusIsReadable() throws Exception {
        DocumentFacadeService documents = mock(DocumentFacadeService.class);
        IngestionService ingestion = mock(IngestionService.class);
        when(ingestion.status(DOCUMENT)).thenReturn(Optional.of(job()));

        mvc(documents, ingestion).perform(get("/api/demo/documents/" + DOCUMENT + "/ingestion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value(DOCUMENT))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        verify(documents).requireReadableDocument(anyString(), any());
        // 只读：唯一一次交互是查状态，没有触发 retry。
        verify(ingestion).status(DOCUMENT);
        verify(ingestion, never()).retry(anyString());
    }

    @Test
    @DisplayName("私有或不存在的文档返回 404，并且不泄露导入任务")
    void privateDocumentIsHidden() throws Exception {
        DocumentFacadeService documents = mock(DocumentFacadeService.class);
        IngestionService ingestion = mock(IngestionService.class);
        doThrow(DocumentContentException.notFound())
                .when(documents).requireReadableDocument(anyString(), any(Predicate.class));

        mvc(documents, ingestion).perform(get("/api/demo/documents/" + DOCUMENT + "/ingestion"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(ingestion, org.mockito.Mockito.never()).status(anyString());
    }

    @Test
    @DisplayName("没有导入任务时按 404 处理，不返回空壳成功")
    void missingJobIsNotFound() throws Exception {
        DocumentFacadeService documents = mock(DocumentFacadeService.class);
        IngestionService ingestion = mock(IngestionService.class);
        when(ingestion.status(DOCUMENT)).thenReturn(Optional.empty());

        mvc(documents, ingestion).perform(get("/api/demo/documents/" + DOCUMENT + "/ingestion"))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mvc(DocumentFacadeService documents, IngestionService ingestion) {
        DemoProperties properties = new DemoProperties();
        properties.setAgentId("11111111-1111-4111-8111-111111111111");
        properties.setPublicKbIds(List.of("88888888-8888-4888-8888-888888888888"));
        return MockMvcBuilders.standaloneSetup(
                        new DemoIngestionController(documents, ingestion, new DemoAccessPolicy(properties),
                                new DemoVisitor()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static IngestionJobView job() {
        return new IngestionJobView("99999999-9999-4999-8999-999999999999", DOCUMENT,
                "88888888-8888-4888-8888-888888888888", "SUCCEEDED", 1, 1, 3, 3, null, null,
                false, null, null, null);
    }
}
