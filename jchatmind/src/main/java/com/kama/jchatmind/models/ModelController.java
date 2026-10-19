package com.kama.jchatmind.models;

import com.kama.jchatmind.auth.AuthContext;
import com.kama.jchatmind.model.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ModelController {
    private final ModelCatalogService catalog;
    private final ModelRuntimeService runtime;

    public ModelController(ModelCatalogService catalog, ModelRuntimeService runtime) {
        this.catalog = catalog;
        this.runtime = runtime;
    }

    @GetMapping("/models")
    public ResponseEntity<?> models() {
        AuthContext.requireUser();
        return response(Map.of("models", catalog.enabledModels()));
    }

    @GetMapping("/admin/models")
    public ResponseEntity<?> adminModels() {
        AuthContext.requireAdmin();
        return response(Map.of("models", catalog.adminModels()));
    }

    @PostMapping("/admin/models")
    public ResponseEntity<?> create(@RequestBody ModelUpsertRequest request) {
        AuthContext.requireAdmin();
        return response(Map.of("model", catalog.create(request)));
    }

    @PatchMapping("/admin/models/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ModelUpsertRequest request) {
        AuthContext.requireAdmin();
        return response(Map.of("model", catalog.update(id, request)));
    }

    @DeleteMapping("/admin/models/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        AuthContext.requireAdmin();
        catalog.delete(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success());
    }

    @PostMapping("/admin/models/{id}/test")
    public ResponseEntity<?> test(@PathVariable String id) {
        AuthContext.requireAdmin();
        return response(runtime.test(catalog.requireModel(id)));
    }

    @GetMapping("/admin/model-bindings")
    public ResponseEntity<?> knowledgeBaseBindings() {
        AuthContext.requireAdmin();
        return response(Map.of("bindings", catalog.knowledgeBaseBindings()));
    }

    @PostMapping("/admin/models/{id}/knowledge-bases")
    public ResponseEntity<?> rebindKnowledgeBases(@PathVariable String id,
                                                  @RequestBody ModelRebindRequest request) {
        AuthContext.requireAdmin();
        return response(catalog.rebindKnowledgeBases(id, request));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> invalidRequest() {
        return ResponseEntity.badRequest().body(ApiResponse.error(400, "模型配置格式无效，请检查填写内容"));
    }

    private ResponseEntity<?> response(Object data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }
}
