package com.pulseticket.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulseticket.common.context.UserContext;
import com.pulseticket.common.exception.BizException;
import com.pulseticket.common.result.Result;
import com.pulseticket.common.security.JwtUtils;
import com.pulseticket.user.entity.PointsLog;
import com.pulseticket.user.entity.UserAccount;
import com.pulseticket.user.entity.UserMessage;
import com.pulseticket.user.mapper.PointsLogMapper;
import com.pulseticket.user.mapper.UserAccountMapper;
import com.pulseticket.user.mapper.UserMessageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserAccountMapper userAccountMapper;
    private final PointsLogMapper pointsLogMapper;
    private final UserMessageMapper userMessageMapper;

    @Value("${pulseticket.jwt.secret:PulseTicketMicroserviceGatewayJwtSecretKey2026EnterpriseEdition8899}")
    private String jwtSecret;

    public UserController(
            UserAccountMapper userAccountMapper,
            PointsLogMapper pointsLogMapper,
            UserMessageMapper userMessageMapper
    ) {
        this.userAccountMapper = userAccountMapper;
        this.pointsLogMapper = pointsLogMapper;
        this.userMessageMapper = userMessageMapper;
    }

    @PostMapping("/auth/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();
        if (username.isEmpty() || password.isEmpty()) {
            throw new BizException("请输入用户名和密码");
        }

        UserAccount user = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username)
        );
        if (user == null || !user.getPasswordHash().equals(password)) {
            throw new BizException(401, "用户名或密码错误（预置演示密码：Ticket@2026）");
        }

        String token = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getVipLevel(), jwtSecret);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", token);
        data.put("user", toUserVO(user));
        return Result.ok("登录成功", data);
    }

    @GetMapping("/profile")
    public Result<Map<String, Object>> getProfile() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未获取到网关透传的 ThreadLocal 登录态");
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }

        List<PointsLog> logs = pointsLogMapper.selectList(
                new LambdaQueryWrapper<PointsLog>()
                        .eq(PointsLog::getUserId, userId)
                        .orderByDesc(PointsLog::getCreatedAt)
                        .last("LIMIT 20")
        );

        List<UserMessage> messages = userMessageMapper.selectList(
                new LambdaQueryWrapper<UserMessage>()
                        .eq(UserMessage::getUserId, userId)
                        .orderByDesc(UserMessage::getCreatedAt)
                        .last("LIMIT 20")
        );

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", toUserVO(user));
        data.put("threadLocalUserId", userId);
        data.put("threadLocalUsername", UserContext.getUsername());
        data.put("traceId", UserContext.getTraceId());
        data.put("pointsLogs", logs);
        data.put("messages", messages);
        return Result.ok(data);
    }

    @PostMapping("/messages/{id}/read")
    public Result<Void> markMessageRead(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        UserMessage msg = userMessageMapper.selectById(id);
        if (msg != null && msg.getUserId().equals(userId)) {
            msg.setIsRead(1);
            userMessageMapper.updateById(msg);
        }
        return Result.ok();
    }

    private Map<String, Object> toUserVO(UserAccount u) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", u.getId());
        vo.put("username", u.getUsername());
        vo.put("nickname", u.getNickname());
        vo.put("phone", u.getPhone());
        vo.put("vipLevel", u.getVipLevel());
        vo.put("pointsBalance", u.getPointsBalance());
        vo.put("totalOrders", u.getTotalOrders());
        return vo;
    }
}
