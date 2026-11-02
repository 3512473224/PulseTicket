-- ============================================================================
-- PulseTicket · 企业级演出与电影在线选座微服务系统 (MySQL 8.0 三库分治初始化脚本)
-- 包含: ticket_user (用户/积分/站内信库) / ticket_show (演出/场次/座位库存库) / ticket_order (订单/Seata审计/MQ日志库)
-- 每个独立数据库均内置 Seata AT 模式标准 undo_log 回滚镜像表
-- ============================================================================

SET NAMES utf8mb4;

-- ============================================================================
-- 1. 用户与会员积分库: ticket_user
-- ============================================================================
DROP DATABASE IF EXISTS ticket_user;
CREATE DATABASE ticket_user CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ticket_user;

CREATE TABLE user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE COMMENT '登录用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码哈希',
    nickname VARCHAR(64) NOT NULL COMMENT '用户昵称',
    phone VARCHAR(32) NOT NULL COMMENT '真实手机号(必填)',
    vip_level VARCHAR(32) NOT NULL DEFAULT 'GOLD' COMMENT '会员等级: NORMAL / GOLD / BLACK_DIAMOND',
    role VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '角色权限: USER(普通购票用户) / ADMIN(架构运维专员)',
    points_balance INT NOT NULL DEFAULT 0 COMMENT '当前可用积分余额',
    total_orders INT NOT NULL DEFAULT 0 COMMENT '累计购票单数',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB COMMENT='用户账户与会员积分主表';

CREATE TABLE points_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    order_no VARCHAR(64) NOT NULL COMMENT '关联票务订单号',
    biz_type VARCHAR(32) NOT NULL COMMENT '业务类型: ORDER_PAID_REWARD / SIGN_IN',
    points_delta INT NOT NULL COMMENT '变动积分(+/-)',
    balance_after INT NOT NULL COMMENT '变动后积分余额',
    remark VARCHAR(255) NOT NULL COMMENT '积分流水说明',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_biz (order_no, biz_type),
    KEY idx_user_created (user_id, created_at DESC)
) ENGINE=InnoDB COMMENT='会员积分变动流水表(唯一索引保障MQ消费100%幂等)';

CREATE TABLE user_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    order_no VARCHAR(64) NOT NULL COMMENT '关联订单号',
    msg_type VARCHAR(32) NOT NULL COMMENT '消息类型: TICKET_ISSUED / ORDER_CLOSED / SYSTEM',
    title VARCHAR(128) NOT NULL COMMENT '站内信标题',
    content VARCHAR(512) NOT NULL COMMENT '站内信正文(含取票码/座位信息)',
    is_read TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已读',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_order_msg_type (order_no, msg_type),
    KEY idx_user_msg (user_id, created_at DESC)
) ENGINE=InnoDB COMMENT='用户出票通知与站内信表(唯一索引保障MQ消费幂等)';

CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info (before/after image)',
    log_status INT(11) NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB COMMENT='Seata AT mode undo_log table';

-- 预置账号：1 个架构运维专员账号 (admin / Admin@2026) + 3 个普通购票用户 (统一密码: Ticket@2026，首次登录自动升级 BCrypt 哈希)
INSERT INTO user_account (id, username, password_hash, nickname, phone, vip_level, role, points_balance, total_orders) VALUES
(1000, 'admin',   'Admin@2026',  '系统架构师',       '18800000001', 'BLACK_DIAMOND', 'ADMIN', 9999, 0),
(1001, 'zhaolan', 'Ticket@2026', '赵岚 · 黑钻观演家', '13820240336', 'BLACK_DIAMOND', 'USER',  2680, 5),
(1002, 'linfeng', 'Ticket@2026', '林峰 · 金卡乐迷',   '13910868821', 'GOLD',          'USER',  920,  2),
(1003, 'suwan',   'Ticket@2026', '苏晚 · 影迷达人',   '13760125519', 'NORMAL',        'USER',  300,  1);

INSERT INTO points_log (user_id, order_no, biz_type, points_delta, balance_after, remark) VALUES
(1001, 'TK202610200001', 'ORDER_PAID_REWARD', 1280, 2680, '购票出票成功奖励积分 (1元=10积分)');

INSERT INTO user_message (user_id, order_no, msg_type, title, content, is_read) VALUES
(1001, 'TK202610200001', 'TICKET_ISSUED', '【出票成功】周杰伦「嘉年华」世界巡回演唱会·上海站', '尊敬的赵岚，您的电子票已签发！座位：内场VIP区 1排06座，取票核验码：PT-8839-2026，请凭身份证原件入场。', 0);


-- ============================================================================
-- 2. 演出场次与座位库存库: ticket_show
-- ============================================================================
DROP DATABASE IF EXISTS ticket_show;
CREATE DATABASE ticket_show CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ticket_show;

CREATE TABLE show_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    show_code VARCHAR(32) NOT NULL UNIQUE COMMENT '演出编码',
    title VARCHAR(128) NOT NULL COMMENT '演出主标题',
    subtitle VARCHAR(255) NOT NULL COMMENT '副标题/巡演亮色',
    category VARCHAR(32) NOT NULL COMMENT '分类: CONCERT / MUSICAL / IMAX_MOVIE / LIVEHOUSE',
    city VARCHAR(32) NOT NULL COMMENT '演出城市',
    venue_name VARCHAR(128) NOT NULL COMMENT '场馆名称',
    poster_gradient VARCHAR(128) NOT NULL COMMENT '视觉主题渐变色',
    badge_text VARCHAR(32) NOT NULL COMMENT '角标文案',
    rating DECIMAL(3,1) NOT NULL DEFAULT 9.8 COMMENT '观众评分',
    min_price DECIMAL(10,2) NOT NULL COMMENT '最低票价',
    max_price DECIMAL(10,2) NOT NULL COMMENT '最高票价',
    tags VARCHAR(255) NOT NULL COMMENT '标签(逗号分隔)',
    description TEXT NOT NULL COMMENT '演出详情介绍',
    hot_score INT NOT NULL DEFAULT 9900 COMMENT '热度指数',
    status VARCHAR(16) NOT NULL DEFAULT 'ON_SALE' COMMENT '状态: ON_SALE / SOLD_OUT',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB COMMENT='演出剧目主信息表(支持Redis缓存三防保护)';

CREATE TABLE show_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    show_id BIGINT NOT NULL COMMENT '所属演出ID',
    session_code VARCHAR(32) NOT NULL UNIQUE COMMENT '场次编码',
    session_title VARCHAR(128) NOT NULL COMMENT '场次名称(如 周六黄金夜场)',
    hall_name VARCHAR(64) NOT NULL COMMENT '演出厅/舞台名',
    start_time DATETIME NOT NULL COMMENT '开演时间',
    end_time DATETIME NOT NULL COMMENT '散场时间',
    total_seats INT NOT NULL DEFAULT 48 COMMENT '总座位数',
    available_seats INT NOT NULL DEFAULT 48 COMMENT '可售座位数',
    locked_seats INT NOT NULL DEFAULT 0 COMMENT '锁定待支付座位数',
    sold_seats INT NOT NULL DEFAULT 0 COMMENT '已售出座位数',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_show_id (show_id)
) ENGINE=InnoDB COMMENT='演出排期场次表';

CREATE TABLE seat_inventory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL COMMENT '场次ID',
    show_id BIGINT NOT NULL COMMENT '演出ID',
    row_no INT NOT NULL COMMENT '排号(1~6)',
    col_no INT NOT NULL COMMENT '座号(1~8)',
    seat_label VARCHAR(32) NOT NULL COMMENT '座位显示名(如 1排03座)',
    zone_code VARCHAR(32) NOT NULL COMMENT '票档区: VIP / STAGE_SIDE / STANDARD',
    zone_name VARCHAR(32) NOT NULL COMMENT '票档中文名',
    price DECIMAL(10,2) NOT NULL COMMENT '单座票价',
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态: AVAILABLE / LOCKED / SOLD',
    locked_order_no VARCHAR(64) DEFAULT NULL COMMENT '当前锁定或售出关联订单号',
    locked_user_id BIGINT DEFAULT NULL COMMENT '锁定用户ID',
    locked_at DATETIME(3) DEFAULT NULL COMMENT '锁座时间',
    sold_at DATETIME(3) DEFAULT NULL COMMENT '出票时间',
    version INT NOT NULL DEFAULT 0 COMMENT 'CAS乐观锁版本号',
    UNIQUE KEY uk_session_row_col (session_id, row_no, col_no),
    KEY idx_session_status (session_id, status),
    KEY idx_locked_order (locked_order_no)
) ENGINE=InnoDB COMMENT='场馆座位实时库存表(Redisson MultiLock + MySQL CAS + Seata AT)';

CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info (before/after image)',
    log_status INT(11) NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB COMMENT='Seata AT mode undo_log table';

INSERT INTO show_info (id, show_code, title, subtitle, category, city, venue_name, poster_gradient, badge_text, rating, min_price, max_price, tags, description, hot_score, status) VALUES
(1, 'SH-CYBER-2026', '「星际回响 · PulseLive」2026 全息交响电音巡演', '沉浸式 360° 环形激光穹顶 · 柏林电子乐团联袂呈现', 'CONCERT', '上海', '梅赛德斯-奔驰文化中心 · 主场馆', 'from-indigo-600 via-violet-600 to-fuchsia-600', '年度必看爆款', 9.9, 380.00, 1280.00, '全息舞台,顺丰电子票,支持在线选座,秒杀防重保护', '融合古典交响编制与先锋模块合成器声场，全场配备数控像素手环与 120 组追踪激光阵列，打造现象级视听盛宴。', 9980, 'ON_SALE'),
(2, 'BJ-PHANTOM-2026', '百老汇原版音乐剧《歌剧魅影 Phantom》20周年纪念版', '伦敦西区原班卡司 · 璀璨水晶吊灯实景升降', 'MUSICAL', '北京', '天桥艺术中心 · 大剧场', 'from-amber-600 via-rose-600 to-purple-800', '殿堂级经典', 9.8, 280.00, 980.00, '原版英文演出,中文字幕,黄金中轴席位', '安德鲁·劳埃德·韦伯传世巨作，现场 35 人管弦乐队伴奏，1:1 复刻巴黎歌剧院地下迷宫与华丽舞会。', 9640, 'ON_SALE'),
(3, 'SZ-INTERSTELLAR-IMAX', '《星际穿越 Interstellar》十周年 IMAX GT 激光重映礼', '1.43:1 满画幅双机激光 · 汉斯·季默管风琴震撼原声', 'IMAX_MOVIE', '深圳', '万象城 IMAX GT 巨幕影厅', 'from-sky-600 via-blue-700 to-slate-900', 'IMAX 1.43满画幅', 9.9, 89.00, 199.00, 'IMAX激光巨幕,纪念金属票根,黄金皇帝位', '穿越虫洞与卡冈都亚黑洞视界，在 32 米宽 IMAX GT 巨幕与 12 声道音响系统中重温宇宙级浪漫。', 9520, 'ON_SALE'),
(4, 'HZ-JAZZ-NIGHT', '西湖仲夏夜 · 蓝调爵士与后摇不插电音乐节', '湖畔星空露台 · 独立乐队四小时连演', 'LIVEHOUSE', '杭州', '大麦 66 Livehouse · 旗舰厅', 'from-emerald-600 via-teal-600 to-cyan-800', '高口碑独立现场', 9.7, 180.00, 480.00, '含特调饮品,前排互动区,黑胶签售', '集结国内顶尖融合爵士四重奏与器乐摇滚乐团，在晚风与精密声学空间中度过难忘周末。', 8890, 'ON_SALE');

INSERT INTO show_session (id, show_id, session_code, session_title, hall_name, start_time, end_time, total_seats, available_seats, locked_seats, sold_seats) VALUES
(101, 1, 'SES-20261115-SH', '11月15日 周六 19:30 首演黄金场', '中央全息穹顶大厅', '2026-11-15 19:30:00', '2026-11-15 22:15:00', 48, 44, 0, 4),
(102, 1, 'SES-20261116-SH', '11月16日 周日 19:30 返场加演夜', '中央全息穹顶大厅', '2026-11-16 19:30:00', '2026-11-16 22:15:00', 48, 48, 0, 0),
(201, 2, 'SES-20261120-BJ', '11月20日 周五 19:30 首演礼宾场', '歌剧大剧场一层的厅', '2026-11-20 19:30:00', '2026-11-20 22:10:00', 48, 46, 0, 2),
(301, 3, 'SES-20261108-SZ', '11月08日 周六 20:00 IMAX GT 零点纪念场', '1号 IMAX GT 激光巨幕厅', '2026-11-08 20:00:00', '2026-11-08 22:50:00', 48, 46, 0, 2),
(401, 4, 'SES-20261112-HZ', '11月12日 周四 20:30 星空不插电专场', 'Livehouse 全景主厅', '2026-11-12 20:30:00', '2026-11-12 23:00:00', 48, 48, 0, 0);

-- 生成每个场次 6排 x 8列 = 48 个可视化座位
-- 第 1~2 排: 内场前区 VIP 席 / 黄金席; 第 3~4 排: 舞台中轴优选席; 第 5~6 排: 看台标准席
DELIMITER $$
CREATE PROCEDURE seed_all_seats()
BEGIN
    DECLARE v_sess_id BIGINT;
    DECLARE v_show_id BIGINT;
    DECLARE v_p_vip DECIMAL(10,2);
    DECLARE v_p_mid DECIMAL(10,2);
    DECLARE v_p_std DECIMAL(10,2);
    DECLARE r INT;
    DECLARE c INT;
    DECLARE v_zone_code VARCHAR(32);
    DECLARE v_zone_name VARCHAR(32);
    DECLARE v_price DECIMAL(10,2);
    DECLARE v_status VARCHAR(16);
    DECLARE v_order_no VARCHAR(64);
    DECLARE done INT DEFAULT 0;

    DECLARE cur CURSOR FOR SELECT id, show_id FROM show_session;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN cur;
    read_loop: LOOP
        FETCH cur INTO v_sess_id, v_show_id;
        IF done THEN
            LEAVE read_loop;
        END IF;

        IF v_show_id = 1 THEN
            SET v_p_vip = 1280.00; SET v_p_mid = 680.00; SET v_p_std = 380.00;
        ELSEIF v_show_id = 2 THEN
            SET v_p_vip = 980.00;  SET v_p_mid = 580.00; SET v_p_std = 280.00;
        ELSEIF v_show_id = 3 THEN
            SET v_p_vip = 199.00;  SET v_p_mid = 139.00; SET v_p_std = 89.00;
        ELSE
            SET v_p_vip = 480.00;  SET v_p_mid = 280.00; SET v_p_std = 180.00;
        END IF;

        SET r = 1;
        WHILE r <= 6 DO
            SET c = 1;
            WHILE c <= 8 DO
                IF r <= 2 THEN
                    SET v_zone_code = 'VIP';
                    SET v_zone_name = '内场至尊VIP区';
                    SET v_price = v_p_vip;
                ELSEIF r <= 4 THEN
                    SET v_zone_code = 'STAGE_SIDE';
                    SET v_zone_name = '中轴声场优选区';
                    SET v_price = v_p_mid;
                ELSE
                    SET v_zone_code = 'STANDARD';
                    SET v_zone_name = '全景看台标准区';
                    SET v_price = v_p_std;
                END IF;

                SET v_status = 'AVAILABLE';
                SET v_order_no = NULL;

                -- 预置少量已售座位让选座图更真实直观
                IF v_sess_id = 101 AND r = 1 AND c IN (4, 5) THEN
                    SET v_status = 'SOLD';
                    SET v_order_no = 'TK202610200001';
                ELSEIF v_sess_id = 101 AND r = 2 AND c IN (4, 5) THEN
                    SET v_status = 'SOLD';
                    SET v_order_no = 'TK202610200002';
                ELSEIF v_sess_id = 201 AND r = 1 AND c IN (4, 5) THEN
                    SET v_status = 'SOLD';
                    SET v_order_no = 'TK202610210001';
                ELSEIF v_sess_id = 301 AND r = 3 AND c IN (4, 5) THEN
                    SET v_status = 'SOLD';
                    SET v_order_no = 'TK202610220001';
                END IF;

                INSERT INTO seat_inventory (
                    session_id, show_id, row_no, col_no, seat_label,
                    zone_code, zone_name, price, status, locked_order_no
                ) VALUES (
                    v_sess_id, v_show_id, r, c,
                    CONCAT(r, '排', LPAD(c, 2, '0'), '座'),
                    v_zone_code, v_zone_name, v_price, v_status, v_order_no
                );

                SET c = c + 1;
            END WHILE;
            SET r = r + 1;
        END WHILE;
    END LOOP;
    CLOSE cur;
END$$
DELIMITER ;

CALL seed_all_seats();
DROP PROCEDURE IF EXISTS seed_all_seats;


-- ============================================================================
-- 3. 票务订单与分布式事务审计库: ticket_order
-- ============================================================================
DROP DATABASE IF EXISTS ticket_order;
CREATE DATABASE ticket_order CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE ticket_order;

CREATE TABLE ticket_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '全局唯一票务订单号',
    user_id BIGINT NOT NULL COMMENT '下单用户ID',
    username VARCHAR(64) NOT NULL COMMENT '下单用户名',
    show_id BIGINT NOT NULL COMMENT '演出ID',
    show_title VARCHAR(128) NOT NULL COMMENT '演出标题快照',
    venue_name VARCHAR(128) NOT NULL COMMENT '场馆名称快照',
    session_id BIGINT NOT NULL COMMENT '场次ID',
    session_title VARCHAR(128) NOT NULL COMMENT '场次名称快照',
    start_time DATETIME NOT NULL COMMENT '开演时间快照',
    seat_ids_json VARCHAR(255) NOT NULL COMMENT '锁定座位ID数组JSON',
    seat_labels_json VARCHAR(512) NOT NULL COMMENT '座位标签数组JSON',
    seat_count INT NOT NULL COMMENT '购票张数',
    total_amount DECIMAL(10,2) NOT NULL COMMENT '订单应付总金额',
    status VARCHAR(24) NOT NULL DEFAULT 'UNPAID' COMMENT '状态: UNPAID / PAID / CLOSED',
    close_reason VARCHAR(64) DEFAULT NULL COMMENT '关单原因: TIMEOUT_UNPAID / USER_CANCEL',
    delay_seconds INT NOT NULL DEFAULT 900 COMMENT '延迟关单秒数(默认900s=15min, 支持20s极速演示)',
    seata_xid VARCHAR(128) DEFAULT NULL COMMENT 'Seata AT 全局事务 XID',
    ticket_code VARCHAR(64) DEFAULT NULL COMMENT '支付出票后生成的电子核验码',
    expire_at DATETIME(3) NOT NULL COMMENT '锁座支付截止时间',
    paid_at DATETIME(3) DEFAULT NULL COMMENT '支付完成时间',
    closed_at DATETIME(3) DEFAULT NULL COMMENT '交易关闭时间',
    version INT NOT NULL DEFAULT 0 COMMENT 'CAS乐观锁版本号(防14:59付款与15:00关单竞态冲突)',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_user_status (user_id, status, created_at DESC),
    KEY idx_session_status (session_id, status)
) ENGINE=InnoDB COMMENT='演出选座订单主表';

CREATE TABLE seata_tx_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    xid VARCHAR(128) NOT NULL COMMENT 'Seata 全局事务 XID',
    order_no VARCHAR(64) NOT NULL COMMENT '关联订单号',
    user_id BIGINT NOT NULL COMMENT '发起用户ID',
    session_id BIGINT NOT NULL COMMENT '场次ID',
    seat_ids VARCHAR(128) NOT NULL COMMENT '涉及座位ID列表',
    tx_status VARCHAR(32) NOT NULL COMMENT 'COMMITTED / ROLLED_BACK',
    fault_injected TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否开启了Seata故障回滚注入演示',
    undo_summary VARCHAR(512) NOT NULL COMMENT '一阶段before/after镜像与二阶段提交/回滚说明',
    latency_ms BIGINT NOT NULL DEFAULT 0 COMMENT '分布式事务耗时(ms)',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_created (created_at DESC)
) ENGINE=InnoDB COMMENT='Seata AT 分布式事务可视化审计表(独立事务REQUIRES_NEW记录回滚轨迹)';

CREATE TABLE mq_event_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL UNIQUE COMMENT 'MQ全局消息唯一ID',
    exchange_name VARCHAR(64) NOT NULL COMMENT '交换机名',
    routing_key VARCHAR(64) NOT NULL COMMENT '路由键',
    order_no VARCHAR(64) NOT NULL COMMENT '关联订单号',
    event_type VARCHAR(32) NOT NULL COMMENT 'DELAY_CLOSE_SENT / TIMEOUT_CLOSED / ORDER_PAID_FANOUT',
    payload_summary VARCHAR(255) NOT NULL COMMENT '消息摘要',
    status VARCHAR(24) NOT NULL DEFAULT 'DELIVERED' COMMENT 'DELIVERED / CONSUMED / SKIPPED_IDEMPOTENT',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_order_no (order_no),
    KEY idx_created (created_at DESC)
) ENGINE=InnoDB COMMENT='RabbitMQ 延迟关单与广播消费全链路追踪表';

CREATE TABLE undo_log (
    branch_id BIGINT NOT NULL COMMENT 'branch transaction id',
    xid VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    rollback_info LONGBLOB NOT NULL COMMENT 'rollback info (before/after image)',
    log_status INT(11) NOT NULL COMMENT '0:normal status,1:defense status',
    log_created DATETIME(6) NOT NULL COMMENT 'create datetime',
    log_modified DATETIME(6) NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id),
    KEY ix_log_created (log_created)
) ENGINE=InnoDB COMMENT='Seata AT mode undo_log table';

-- 预置历史已出票订单与审计轨迹（与 seat_inventory 预置已售席位 100% 严格对齐）
INSERT INTO ticket_order (
    id, order_no, user_id, username, show_id, show_title, venue_name,
    session_id, session_title, start_time, seat_ids_json, seat_labels_json,
    seat_count, total_amount, status, delay_seconds, seata_xid, ticket_code,
    expire_at, paid_at
) VALUES
(
    1, 'TK202610200001', 1001, 'zhaolan', 1,
    '「星际回响 · PulseLive」2026 全息交响电音巡演', '梅赛德斯-奔驰文化中心 · 主场馆',
    101, '11月15日 周六 19:30 首演黄金场', '2026-11-15 19:30:00',
    '[4,5]', '["内场至尊VIP区 1排04座","内场至尊VIP区 1排05座"]', 2, 2560.00, 'PAID', 900,
    '127.0.0.1:8091:20261020888001', 'PT-8839-2026',
    '2026-10-20 20:15:00', '2026-10-20 20:01:12'
),
(
    2, 'TK202610200002', 1002, 'linfeng', 1,
    '「星际回响 · PulseLive」2026 全息交响电音巡演', '梅赛德斯-奔驰文化中心 · 主场馆',
    101, '11月15日 周六 19:30 首演黄金场', '2026-11-15 19:30:00',
    '[12,13]', '["内场至尊VIP区 2排04座","内场至尊VIP区 2排05座"]', 2, 2560.00, 'PAID', 900,
    '127.0.0.1:8091:20261020888002', 'PT-6612-0002',
    '2026-10-20 21:15:00', '2026-10-20 21:03:08'
),
(
    3, 'TK202610210001', 1002, 'linfeng', 2,
    '百老汇原版音乐剧《歌剧魅影 Phantom》20周年纪念版', '天桥艺术中心 · 大剧场',
    201, '11月20日 周五 19:30 首演礼宾场', '2026-11-20 19:30:00',
    '[100,101]', '["内场至尊VIP区 1排04座","内场至尊VIP区 1排05座"]', 2, 1960.00, 'PAID', 900,
    '127.0.0.1:8091:20261021888001', 'PT-7520-0001',
    '2026-10-21 19:45:00', '2026-10-21 19:32:45'
),
(
    4, 'TK202610220001', 1003, 'suwan', 3,
    '《星际穿越 Interstellar》十周年 IMAX GT 激光重映礼', '万象城 IMAX GT 巨幕影厅',
    301, '11月08日 周六 20:00 IMAX GT 零点纪念场', '2026-11-08 20:00:00',
    '[164,165]', '["中轴声场优选区 3排04座","中轴声场优选区 3排05座"]', 2, 278.00, 'PAID', 900,
    '127.0.0.1:8091:20261022888001', 'PT-9104-0001',
    '2026-10-22 22:15:00', '2026-10-22 22:04:19'
);

INSERT INTO seata_tx_audit (xid, order_no, user_id, session_id, seat_ids, tx_status, fault_injected, undo_summary, latency_ms) VALUES
('127.0.0.1:8091:20261020888001', 'TK202610200001', 1001, 101, '[4,5]', 'COMMITTED', 0, '一阶段 order-service 插入订单 + show-service 锁定2个VIP座位并写 undo_log；二阶段异步清理 undo_log 完成全局提交', 42);
