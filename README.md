很感谢共济会的大佬们提供的web新版导航页面的代码
ddd5537 and SL
一、核心聊天功能
1. 多频道聊天系统

支持公开频道（内置）和多个私有频道（通过 onlypd.json 配置，每个频道有独立密码）

频道配置支持热加载（每 1.5 秒检测一次文件变化）

消息广播、历史记录保存（存到 chat_history/ 目录）

消息长度限制（300 字节）、连续相同字符检测（5 个）、重复消息检测（10 分钟窗口）

2. 用户管理

昵称设置、在线用户列表（30 秒广播一次）

封禁用户（ban.txt）、禁言用户（muted_users.txt，支持小时级别）

服务器保留名称 "server"

客户端连接超时检测（100 秒）

3. 私聊系统（P2P）

每个用户分配一个 5 位数字私聊码

通过私聊码发起点对点聊天

服务器端也可与用户私聊（密码 00000）

二、实时语音功能
频道实时语音：8kHz 16bit 单声道 PCM，每帧 320 字节，服务器端混音（支持音量倍率 1~6 倍）

私聊语音：一对一的实时语音通话，含申请/接受/拒绝/超时机制

服务器端语音：服务器本身也可以加入频道语音或与用户私聊语音

语音留言：录制 WAV 格式语音消息发送到频道

三、图片传输
分块上传图片（每块 Base64 编码），服务端重组后保存到 onlyph/ 目录

30 秒超时清理机制

四、网页端（HTTPS/WSS）
基于 WebSocket 的网页客户端，需通过验证问题才能访问

支持 HTTPS/WSS（读取证书私钥，ssl-config.json 配置）

网页会话 1 小时无操作自动断开

最低客户端版本限制（version-config.json）

五、DeepSeek AI 问答
通过 /deepseek|问题 调用 DeepSeek API

API Key 从 DeepSeekapi.json 读取（支持热加载）

30 秒冷却、3500 字符限制、敏感词过滤（复用 pbc.txt）

六、小游戏平台（网页端）
四个游戏，都带排行榜（前 100 名，同名取最高分）：

贪吃蛇（35×35）

点格子（20×20，3 秒内点红格）

模拟地球（Three.js 3D 地球，支持下雨/下雪/陨石/大洲冲突）

3D 射击生存（FPS）：

单人模式 + 多人模式（2~16 人，房主可设密码）

武器系统（手枪、UZI、M249、DDR-600），不同伤害/弹匣/射速

机器人敌人 + 红色 Boss（10000 HP，90 秒刷新，召唤小怪）

多人模式采用服务器权威判定：玩家状态同步、命中验证、生命代号防作弊、房间共享道具/Boss

支持触屏虚拟按键

七、肥雪网盘
通过 HTTPS 提供只读文件共享（WebPanFiles）

支持目录浏览、文本预览（自动识别 UTF-8/GBK/UTF-16）、文件下载（支持 Range 断点续传）

需要网页验证通过后才能访问

八、其他管理功能
服务器控制台命令：/chat、/ban、/unban、/kick、/mute、/unmute、/chatone

违禁词过滤（pbc.txt），公开频道生效

游戏战绩记录（game_records.json、buui.json、fps.json）

技术架构要点
传输层：ClientTransport 接口抽象，RawLineTransport（普通 TCP 行协议）+ WebSocketTransport（网页端）

连接分流：HttpFrontend.routeIncomingConnection 根据前 4 字节判断是 HTTP 还是聊天协议

配置管理：ConfigManager 统一处理多个 JSON 配置文件的读写

模块化：UserManager、VoiceManager、MessageGuard、ChatHistoryStore、GameRecordStore、FpsLobbyManager、WebPanService 等
