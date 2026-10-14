package com.kama.jchatmind.auth;

import com.kama.jchatmind.model.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {
    public record Users(List<AuthUser> users) { }
    public record User(AuthUser user) { }
    private final AuthService auth;
    public AdminUserController(AuthService auth) { this.auth = auth; }
    @GetMapping public ApiResponse<Users> list() { return ApiResponse.success(new Users(auth.users())); }
    @PostMapping public ApiResponse<User> create(@RequestBody AuthService.CreateUser request) {
        return ApiResponse.success(new User(auth.create(request)));
    }
    @PatchMapping("/{id}") public ApiResponse<User> update(@PathVariable String id, @RequestBody AuthService.UpdateUser request) {
        return ApiResponse.success(new User(auth.update(id, request)));
    }
}
