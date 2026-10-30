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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
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
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

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
        if (user == null || !verifyPassword(user, password)) {
            throw new BizException(401, "账号或密码不正确");
        }

        String role = (user.getRole() != null && !user.getRole().isBlank()) ? user.getRole() : "USER";
        String token = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getVipLevel(), role, jwtSecret);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", token);
        data.put("user", toUserVO(user));
        return Result.ok("登录成功", data);
    }

    @PostMapping("/auth/register")
    @Transactional(rollbackFor = Exception.class)
    public Result<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();
        String nickname = body.getOrDefault("nickname", "").trim();
        String phone = body.getOrDefault("phone", "").trim();

        if (!username.matches("^[a-zA-Z0-9_]{3,24}$")) {
            throw new BizException("用户名需为 3~24 位字母、数字或下划线");
        }
        if (password.length() < 6) {
            throw new BizException("登录密码长度不能少于 6 位");
        }

        Long exists = userAccountMapper.selectCount(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, username)
        );
        if (exists != null && exists > 0) {
            throw new BizException(409, "该用户名已被注册，请换一个");
        }

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setNickname(nickname.isEmpty() ? username : nickname);
        user.setPhone(maskPhone(phone));
        user.setVipLevel("NORMAL");
        user.setRole("USER");
        user.setPointsBalance(100);
        user.setTotalOrders(0);
        userAccountMapper.insert(user);

        PointsLog bonus = new PointsLog();
        bonus.setUserId(user.getId());
        bonus.setOrderNo("REG-" + user.getId());
        bonus.setBizType("SIGN_IN");
        bonus.setPointsDelta(100);
        bonus.setBalanceAfter(100);
        bonus.setRemark("新会员注册礼遇积分");
        pointsLogMapper.insert(bonus);

        UserMessage welcome = new UserMessage();
        welcome.setUserId(user.getId());
        welcome.setOrderNo("WELCOME-" + user.getId());
        welcome.setMsgType("SYSTEM");
        welcome.setTitle("欢迎加入 PulseTicket");
        welcome.setContent("您的账号已开通，已赠送 100 新人观演积分。选座下单后座位将为您保留 15 分钟。");
        welcome.setIsRead(0);
        userMessageMapper.insert(welcome);

        String token = JwtUtils.generateToken(user.getId(), user.getUsername(), user.getVipLevel(), "USER", jwtSecret);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accessToken", token);
        data.put("user", toUserVO(user));
        return Result.ok("注册成功", data);
    }

    @GetMapping("/profile")
    public Result<Map<String, Object>> getProfile() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(401, "未获取到网关透传的登录态");
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(401, "用户不存在或登录已失效");
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
        data.put("threadLocalRole", UserContext.getRole());
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

    private boolean verifyPassword(UserAccount user, String rawPassword) {
        String stored = user.getPasswordHash();
        if (stored == null) return false;
        if (stored.startsWith("$2a$") || stored.startsWith("$2b$")) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        if (stored.equals(rawPassword)) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            userAccountMapper.updateById(user);
            return true;
        }
        return false;
    }

    private String maskPhone(String phone) {
        if (phone != null && phone.matches("^1\\d{10}$")) {
            return phone.substring(0, 3) + "****" + phone.substring(7);
        }
        return "138****0000";
    }

    private Map<String, Object> toUserVO(UserAccount u) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", u.getId());
        vo.put("username", u.getUsername());
        vo.put("nickname", u.getNickname());
        vo.put("phone", u.getPhone());
        vo.put("vipLevel", u.getVipLevel());
        vo.put("role", (u.getRole() != null && !u.getRole().isBlank()) ? u.getRole() : "USER");
        vo.put("pointsBalance", u.getPointsBalance());
        vo.put("totalOrders", u.getTotalOrders());
        return vo;
    }
}
