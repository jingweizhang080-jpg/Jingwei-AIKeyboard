# 景威AI输入法 V0.6.0 UI Refresh

基于 V0.5.2-safe-insets 继续优化。

本版重点：
- 按参考图重做顶部品牌区与 AI 快捷入口
- AI 功能改为卡片式层级
- 候选词条统一为白色整条
- QWERTY 按键加高至 50dp，圆角和间距重新调整
- 第二排按键左右缩进，更接近成熟输入法布局
- 底栏改为：符 / 123 / ， / 空格 / 。 / 中英 / 回车
- 继续保留 V0.5.2 的底部安全区适配，避免与系统导航键重叠
- 保留中文拼音、AI回复、语音、剪贴板、安全输入框保护等原功能

请在 Android Studio 中打开本目录，等待 Gradle Sync 后安装测试。
# 景威AI键盘 V0.1

这是一个**自用 Android AI 辅助输入法 MVP**。它不是为了替代搜狗/Gboard 的完整中文输入，而是作为“AI侧边输入法”：复制一条消息 → 切换到景威AI键盘 → 生成回复 → 点击候选 → 直接写回微信/QQ/小红书等当前输入框。

## V0.1 已实现

- Android `InputMethodService` 输入法服务
- 读取剪贴板文字
- 读取当前输入框附近文字
- 4 种模式：高情商回复 / 朋友圈 / 客户回复 / 润色
- 每次显示 3 条候选
- 点击候选，通过 `InputConnection.commitText()` 直接插入当前输入框
- 一键打开系统“切换输入法”面板
- 密码/敏感输入框自动暂停AI读取
- 无后端时自动使用本地演示模式
- 可保存：AI代理地址、APP Token、个人表达风格
- 附带 Cloudflare Worker AI代理示例

## 重要说明

V0.1 **没有自己造中文拼音词库**。正常打中文时继续用你原来的输入法；需要AI生成时切到“景威AI键盘”。这是为了先把最有价值的链路跑通，避免第一版就陷入中文输入引擎的大工程。

## 在 Android Studio 中运行

1. 安装 Android Studio。
2. `Open` 本项目文件夹。
3. 等待 Gradle Sync 完成。如果 Android Studio 提示安装 Android SDK 35，按提示安装。
4. 用 USB 连接 Android 手机并打开“USB调试”。
5. 点击 Run，把 Debug 版安装到手机。
6. 打开“景威AI键盘”App。
7. 点“打开输入法设置”，启用“景威AI键盘”。
8. 点“选择输入法”，选择“景威AI键盘”。

## 不接AI服务器也能先测

不填写“AI代理地址”时，App 会进入演示模式。你可以先验证：

- 能否切出键盘
- 能否读取剪贴板
- 能否点模式按钮
- 能否把候选插入微信输入框

演示模式的文字是内置模板，不会真正根据复制内容推理。

## 接入真正AI

推荐架构：

手机输入法 → 你自己的 HTTPS 代理 → OpenAI API

不要把 OpenAI API Key 直接打包进 APK。

`server/cloudflare-worker.js` 是一个最小代理示例。你需要在服务端设置：

- `OPENAI_API_KEY`
- `APP_TOKEN`
- 可选 `OPENAI_MODEL`

部署后，把 Worker 的 `/reply` URL 和同一个 `APP_TOKEN` 填入手机 App 设置。

## 下一版建议（V0.2）

1. 接通真实 AI
2. 候选支持“更温暖 / 更幽默 / 更简短”二次改写
3. 增加“学生回复 / 朋友回复 / 领导回复”场景
4. 增加历史收藏
5. 优化键盘视觉和候选卡片
6. 再评估是否把 Rime 中文输入引擎整合进来，做到真正一体化输入法

## V0.2.1 (local pinyin core)

- Added offline continuous-pinyin candidate lookup based on Rime lexicon data.
- Added a dedicated horizontal pinyin candidate bar, separate from AI results.
- Uses Android composing text instead of permanently inserting raw pinyin first.
- Space selects the first Chinese candidate; delete edits the active pinyin composition.
- Added Chinese/English toggle, comma/period, space, and enter row.
- AI reply / Moments / customer reply / polish features remain intact.
- Ordinary pinyin typing stays local; AI text is sent only when the user explicitly triggers an AI action.

The local Java `PinyinEngine` is an adapter layer. A future version can swap its
implementation for native `librime` while keeping the keyboard UI and AI workflow.

## V0.3 UI prototype
- Rounded white keycaps and more compact daily-use layout
- 123 number/symbol page and #+= extended symbols
- Chinese/English switching retained
- Microphone entry with Android SpeechRecognizer; microphone permission is granted from the app home screen
- AI reply features retained
- Launcher icon remains pending replacement with the user's previously designed final icon asset


## V0.3.1 icon update
- Replaced launcher/input-method app icon with the approved 景威AI键盘 purple-blue chat-bubble + keyboard identity.
- Added density-specific Android launcher resources and a 512px source asset.

## V0.4.1 本轮修复
- 换行键固定执行换行，不再触发发送/完成导致键盘收起。
- 语音输入增加权限检查、系统语音服务检测和详细错误提示。
- 语音识别按中/英文模式自动使用 zh-CN / en-US。
- 键盘按键与 AI 候选区进一步压缩，减少占屏和底部视觉空挡。
- 保留连续拼音分词候选、Emoji、数字/符号、中英文切换及键盘布局入口。

> 注意：语音转文字依赖手机系统提供 Android SpeechRecognizer 服务。首次使用请先打开 App 点击“授权语音输入（麦克风）”。


## V0.5 中文输入核心测试版
- 保留 14 万行本地拼音词库，`nihao`、`jintian`、`woaini` 等可直接命中词组。
- 连续拼音继续采用本地分段候选，不上传普通打字内容。
- 换行键改为直接提交 `\n`，避免聊天软件把 Enter 当成发送/完成并收起键盘。
- 语音继续使用 Android SpeechRecognizer；能否工作取决于手机是否提供可用系统识别服务及麦克风权限。
- 九键/手写/笔画/五笔当前仍为后续模块，不伪装成已完成能力。


## V0.5.1 UI rebuild
- Larger 54dp keycaps and 18sp legends for a more comfortable Baidu/Gboard-like typing feel.
- Compact top toolbar; AI tools collapse into an on-demand drawer.
- Dedicated 42dp horizontal Chinese candidate strip.
- Cleaner gray functional keys and white letter keys with consistent spacing.
- Removed the redundant edit-operation row to give the keyboard body more space.


## V0.5.2 Safe Insets UI
- Added bottom system-navigation inset handling so the keyboard stays above Android navigation buttons/gesture area.
- Reduced vertical heights in keyboard rows/toolbars while keeping large keycaps.
- Preserved AI, Emoji, Chinese/English switch, symbols and voice entry.
- Target: visually closer to mature IMEs while preventing bottom-row overlap on devices using three-button navigation.


## V0.6.1 百度式紧凑布局
- 日常输入高度按成熟中文输入法思路压缩：1 行 AI 工具 + 1 行候选 + 4 行键盘。
- 删除重复品牌栏和常驻 AI 卡片，AI 结果仅在主动触发时临时展开。
- 字母键高度 46dp，缩小纵向间距，保持大键帽但显著降低总高度。
- 底部 Android 导航栏颜色与键盘统一，并避免重复叠加完整系统 inset 导致虚高。
- 保留拼音、Emoji、符号、中英切换、语音入口与 AI 能力。


## V0.6.2
- 拼音和中文候选合并为同一候选栏：`ni hao | 你好 | ...`
- 支持显式音节分隔：`ni'hao`
- 新增简拼兜底：`nh -> 你好`，`h'h'h'h'h / hhhhh -> 哈哈哈哈哈`
- 候选首项蓝色高亮，交互更接近成熟中文输入法
- 底部增加小幅系统导航安全距离，同时缩短键帽高度，避免总高度变高


## V0.6.3 底部导航安全区修复
- Android 11+ 使用 `WindowInsets.Type.navigationBars()` 获取真实系统导航栏高度
- 即使 ColorOS / OxygenOS 返回 0，也固定保留 14dp 触摸安全区
- 安全区最大限制 22dp，避免键盘重新变高
- 键帽高度同步压缩到 42dp，整体高度基本不增加
- 系统导航栏背景改为与键盘一致的浅灰色，并关闭高对比度蒙层
- 继续保留 V0.6.2 的拼音/候选同栏与简拼支持


## V0.6.4
- 底部触摸安全区提升到至少 26dp，进一步避开 ColorOS/一加三键导航栏
- 键帽高度同步压缩为 40dp，避免因为安全区增大导致键盘整体变高
- 新增可用的拼音九键布局：1分词 / 2ABC / 3DEF / 4GHI / 5JKL / 6MNO / 7PQRS / 8TUV / 9WXYZ
- 九键使用本地拼音词库做 T9 数字映射和候选搜索
- 顶部右侧新增“⌨”键盘切换入口，可在 26键 / 9键之间切换
- 九键支持删除、重输、空格、中英切换和回车


## V0.6.5 九键数字泄漏 + AI上下文修复
- 九键每次按键先验证 T9 前缀，无效数字序列直接忽略，不再把 `6442692` 之类数字写进聊天框
- 九键编辑区只显示解析后的拼音，不显示内部数字编码
- AI回复/客户回复每次点击都会重新读取最新剪贴板，避免一直拿上一条消息生成
- 输入法会在当前会话内保存最近 8 条“对方/我”的片段，生成时作为滚动上下文发送
- 点击AI候选上屏后会记为“我”的上一轮回复，下一次AI生成可继续承接
- Worker提示词加强“承接上下文 / 不重复问已知信息 / 不脑补”
- 底部安全区最低 30dp，键帽同步压到39dp，继续减少与系统导航键误触


## V0.6.6 九键长句修复
- 修复“九键输入超过约3个汉字后继续按键无效”
- 根因：V0.6.5 把整串T9数字当成单个拼音音节做前缀验证
- 改为多音节动态切分，不再要求整串数字命中一个词条
- 九键组合长度上限提高到64位
- 长串T9可拆为多个拼音块并组合中文候选
- 拼音显示支持多音节形式，例如 `wo xiang chi fan`


## V0.6.7 百度式九键拼音摆放
- 九键拼音不再挤在候选词同一行左侧
- 新增24dp超薄拼音组合栏：拼音显示在候选栏上方
- 候选词独占下一整行，首选词继续蓝色高亮
- 右侧保留“分词”入口，整体交互更接近百度输入法九键
- 26键仍保留原来的紧凑同栏显示方式
- 同步压缩候选区高度，避免总键盘高度变高


## V0.6.8 九键乱词排序修复
- 不再按词库文件顺序硬拼九键候选
- 新增 `t9_tokens.tsv` 高频词表，使用词频给九键候选打分
- 九键长句改为 beam search 动态解码，最多同时保留约90条高分路径
- 每增加一个错误碎片都会受到分词惩罚，减少“密集区歇下迷”这类乱词
- 优先保留常见整词/词组，例如：你好、今天、想吃、什么、吃饭、怎么样
- 拼音显示和中文候选使用同一条最佳解码路径，减少“上面拼音一个意思、下面候选另一个意思”
- 仍保留后续切换到 librime 的接口空间


## V0.6.9 九键数字不上屏修复
- 中文九键模式下，2-9 数字只保存在输入法内部 T9 buffer，绝不直接 commit 到聊天输入框
- 进入九键时强制切回中文状态，避免误留在英文/数字分支
- 九键内部无完整拼音时保持目标输入框不动，只刷新拼音/候选区
- 切换九键时清理旧 composing 状态，避免上一轮数字或拼音残留
- 中/英切换在英文状态改回26键，避免用户误以为九键中文仍在工作


## V0.6.10 九键退格修复
- 修复九键拼音删除一个按键后整串变成数字的问题
- 中文拼音组合状态下，删除键优先修改内部 `pinyinBuffer`，不直接删聊天框正文
- 删除后立即重新解码并用 `setComposingText()` 更新拼音
- 九键退格绝不回退显示内部 T9 数字
- 如果缩短后的T9序列暂时无法完整解码，会保留可解码的最长拼音前缀，而不是显示数字
- 可以删除一个错误按键后继续补键，重新得到正确中文候选
- 26键拼音删除逻辑同步走同一个组合缓冲区


## V0.6.11 百度式26键拼音界面
- 26键拼音不再和中文候选挤在同一条横栏
- 26键也采用“上方超薄拼音组合栏 + 下方整行中文候选”的结构
- 拼音栏约22dp，候选栏同步压缩，整体键盘高度基本不增加
- 26键隐藏“分词”按钮，保持更接近百度输入法的干净视觉
- 首选中文继续蓝色高亮，候选词获得完整横向空间
- 9键保持现有分词入口与布局


## V0.7.0 个人表达AI
核心入口重新定义：
- 💬 帮我回复：收到别人消息，不知道怎么回
- ✍️ 帮我表达：自己知道意思，但不知道怎么说更合适
- ✨ 润色：已经写好内容，希望表达得更自然
- 更多⌄：承接朋友圈、客户沟通等细分场景

产品方向：
“百度能帮我写，景威AI知道我该怎么说。”
保留 V0.6.11 的九键、26键、拼音删除修复和百度式26键拼音布局。


## V0.7.1 键盘收起修复
- 工具栏最右侧改为清晰的 `⌄` 收起按钮
- 点击 `⌄` 直接调用 InputMethodService.requestHideSelf(0) 收起输入法
- 增加厂商 ROM 的 hideSoftInputFromWindow 兜底
- 收起前结束当前 composing 状态，避免拼音残留
- 长按 `⌄` 仍可打开系统输入法切换器
- Android 返回键也走同一套收起逻辑


## V0.7.2 编译修复
- 删除 V0.7.1 中错误添加的 Activity 风格 `onBackPressed()` override
- 改用 `onKeyDown(KEYCODE_BACK)` 作为返回键收起兜底
- 顶部 `⌄` 收起按钮仍直接调用 `requestHideSelf(0)`
- 保留长按 `⌄` 打开输入法切换器


## V0.7.3 AI面板收起 + 输入方式恢复
- 修复 V0.7.x 中 AI 功能面板展开后无法收回的问题
- AI面板增加独立 `⌃ / ⌄` 展开收起控制，不再和“收起整个键盘”混用
- 恢复 `⌨` 输入方式切换入口
- 输入方式菜单恢复：拼音26键 / 拼音9键 / Emoji / 符号数字
- `更多⌄` 也可打开输入方式选择，避免入口丢失
- 保留 V0.7.2 的整个键盘收起逻辑


## V0.7.4 输入方式切换 + 中间布局重做
- 修复 `⌨` 点了没有九键的问题：取消 InputMethodService 中不稳定的 AlertDialog，改为键盘内部原生选择面板
- 输入方式面板恢复：拼音26键 / 拼音9键 / Emoji / 符号数字
- 候选栏右侧下拉箭头也可打开输入方式面板
- AI面板初始状态改为真正“收起”，修复展开/收起状态反转
- 顶部只保留一排：帮我回复 / 帮我表达 / 润色 / 更多 / 输入方式 / AI收起 / 整键盘收起
- 删除中间重复的四张AI功能卡，中间区域只显示当前内容和生成结果
- AI区域与“收起整个键盘”彻底分离，避免两个下拉键职责混乱


## V0.7.5 完整符号面板
- 将原来的简单符号键盘升级为分类符号面板
- 分类：常用 / 中文 / 英文 / 数学 / 序号 / 箭头 / 特殊
- 增加中文全角标点、英文标点、数学运算符、①~⑩、罗马数字、箭头、星号/勾选/版权等特殊符号
- 符号面板底部可快速返回 ABC 或九键
- 保留空格 / 删除 / 回车
- 点击符号直接上屏，并正确结束当前拼音组合，避免拼音状态错乱
