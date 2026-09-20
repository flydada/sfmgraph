# SFM Graph Editor / SFM 图谱编辑器

给 [Super Factory Manager](https://github.com/TeamDman/SuperFactoryManager) 做的可视化图谱编辑器：用标签枪给方块打上标签后，在管理器里用**节点连线 + 菜单栏**的方式配置「哪个方块从哪一面进、哪一面出、每 tick 搬多少物品/流体/电力」，由编辑器把它编译成 SFML 程序交给 SFM 执行。

**A visual node-graph editor for Super Factory Manager.** Label your blocks with SFM's label gun, then wire them up with nodes instead of writing SFML by hand: pick which face of each block items, fluids and Forge Energy go in or out of, and choose exactly how much moves per tick. The graph is compiled into a normal SFM program, so SFM itself still does all the work. The in-game UI is in Chinese.

| | |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.206（`loaderVersion="[4,)"`） |
| 前置 | Super Factory Manager **4.32.0** 或更高（实际依赖 `maven.modrinth:super-factory-manager:lthZRkjn`） |
| 端 | 功能全在客户端；专用服务器上装不装都行（装了什么也不做） |
| 版本 | 1.0.0 |

---

## 它解决了什么

SFM 本身很强大，但程序要手写：`EVERY 20 TICKS DO INPUT 80 FROM ore OUTPUT TO furnace TOP SIDE END` 这种语句，面、槽位、限额、保留量全都挤在一行里。想改一个数字就得重新数关键字顺序。

这个模组把你看到的东西变成图：每个标签一个节点，每条搬运一条连线，面在节点上点选，速率用滑条或数字框填，底部实时显示它生成的那几行程序——**你随时能看到最终会写进磁盘的是什么**，保存前还会用 SFM 自己的编译器校验一遍。

---

## 下载

已经编译好的 jar 在 [Releases](https://github.com/flydada/sfmgraph/releases)：`sfmgraph-MC1.21.1-1.0.0.jar`。

⚠ 要下的是 **Assets** 里的 jar，不是 GitHub 自动附上的「Source code」zip / tar.gz —— 那两个是源码快照，里面没有编译好的类，装进游戏等于什么都没装。

---

## 安装

1. 装好 NeoForge 1.21.1 和 Super Factory Manager 4.32.0+。
2. 把 `sfmgraph-MC1.21.1-1.0.0.jar` 放进**实例的** mods 文件夹。

   > 用 PCL / HMCL / Prism 这类启动器时开了版本隔离，mods 文件夹在**实例目录**下（例如 `D:\pcl\.minecraft\versions\<版本名>\mods`），不是 `%APPDATA%\.minecraft\mods`。找错文件夹是这种情况最常见的坑。

   > 构建只产出**一个** jar，把那个放进去就行。别的名字（例如带 `-sources` 的）不是可用的模组：它会被 FML 认出来并列进模组列表，但里面没有编译好的类，等于什么都没装。
> **更新模组时必须先完全退出游戏，再替换 jar。**
> Minecraft 是按需从 jar 里读类的：运行中途替换文件，会让**还没被加载过的类**再也找不到，表现为 `NoClassDefFoundError` 崩溃——而且崩在哪个类是随机的，看起来像模组本身有 bug。工程里的 `install.bat` 会检测到游戏在运行并拒绝覆盖：

```bat
install.bat "D:\pcl\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods"
```

3. 启动游戏。（本模组只改界面，不需要服务器端安装。）

启动后日志里会有一行确认它真的加载了：

```
Loaded SFM Graph Editor 1.0.0: registered SFM text editor 'sfmgraph:graph', keybind Ctrl+G (active in the manager screen)
```

搜 `sfmgraph` 找不到这一行，就是没加载（模组列表里出现了它却搜不到这行，则是装错文件了）。

---

## 使用

### 1. 先打标签

用 SFM 的标签枪给要操作的方块打上标签（这一步完全用原版 SFM 的方式）。标签名会直接成为图中的节点名，多个方块可以用同一个标签。

标签名建议用 `ore`、`furnace_input` 这类普通单词。**避免用 `in`、`output`、`to`、`each` 这种和 SFML 关键字撞名的标签**——虽然编辑器会自动给它们加引号来保证程序能解析，但手工编辑程序时会很别扭。

### 2. 打开编辑器

两种方式，任选：

- **按 `Ctrl+G` 键**：先对着管理器右键打开它的界面，然后在界面里按 `Ctrl+G`。编辑器会叠在管理器界面之上，用的正是 SFM 给磁盘程序的那套数据（标签表 + 程序文本 + 保存通道），因此完全不需要服务器端支持。
  - 用组合键而不是单键，是因为大型整合包里单字母键位很挤（开发时对照的那个整合包里有 7 个功能都绑在 `G` 上），`Ctrl+G` 也正好和 SFM 自己的 `Ctrl+E` 对称。
  - 这个键只在**管理器界面打开时**才响应，所以不会和世界里别的模组抢按键；想改键就去 `选项 → 控制` 搜「图谱」。
  - 它是在界面里拦截按键实现的（和 SFM 自己的 `Ctrl+E` 同一套做法）：MC 的常规按键绑定轮询在界面打开时不会派发，所以这是唯一可靠的方式。
- **让管理器的「编辑」按钮直接打开它**：在编辑器里点 `文件 → 设为管理器的默认编辑器`，它会写 SFM 的客户端配置。重启后若没保留，手动改 `config/sfm-client-program-editor.toml`：

  ```toml
  preferredEditor = "sfmgraph:graph"
  ```

  这样管理器界面上的编辑按钮就会进入图谱编辑器（想切回去就把值改回 `sfm:v1`）。

### 3. 画图

| 操作 | 效果 |
|---|---|
| 左键拖节点 | 移动（默认对齐 8 格网格） |
| 从节点**右侧圆点**拖到另一个节点 | 新建连线 |
| 左键点节点 / 点连线 | 选中，右侧检查器显示它的设置 |
| 左键拖空白 | 框选节点 |
| 中键拖 / 方向键 | 平移画布 |
| 滚轮 | 以光标为中心缩放 |
| 右键 | 右键菜单（节点、连线、空白处各有不同） |
| 双击检查器里的文本框 | 直接输入数字/ID |
| `F` | 适应视图 |
| `G` | 开关网格 |
| `Ctrl+Z` / `Ctrl+Y` | 撤销 / 重做 |
| `Ctrl+S` | 保存到磁盘 |
| `Delete` | 删除选中 |

菜单栏里还有：批量改选中连线的速率/资源类型、反转方向、启用禁用、用 SFM 编译器校验、复制生成的程序、复制原程序。

### 4. 配置一条连线

检查器从上到下是：

- **资源类型**：物品 / 流体 / 电力（FE）。
- **过滤器**：留空表示「所有物品」；填 `minecraft:iron_ingot` 精确到物品，填 `*ingot*` 用通配符；流体填 `water` 或 `minecraft:water`。旁边的「手持」按钮直接取主手物品当过滤器。
- **速率**：对数滑条 + 精确输入框，单位是「每 tick」（红石触发时是「每次脉冲」）。数值可以是小数，比如 `1.5`、`0.001`。
- **源面 / 目标面**：点选面（见下）。
- **触发**：`每 N tick` 或 `红石脉冲`。
- **高级设置**：槽位、RETAIN、每方块独立限额、每种资源独立限额、只填空槽位、轮流选择、条件（有红石 / 库存 > n）。
- **生成结果**：这条连线会变成哪几行程序，实时更新。

### 5. 保存

`Ctrl+S` 或 `文件 → 保存到磁盘`。保存前会自动跑一遍 SFM 的真实编译器，有问题会先列出来让你决定「返回修改」还是「仍然保存」。保存后程序写进管理器的磁盘，SFM 立刻开始按它工作。

---

## 速率是怎么变成程序的（重要）

**SFM 没有「每 tick 传输多少」这种设置。** 它的模型是：一条语句每次执行搬走固定数量，由触发间隔决定执行频率。所以编辑器做的是换算：

```
每 tick 速率 r  ==  EVERY N TICKS DO  INPUT (r × N) ... END
```

N 不能随便取：服务器配置 `timerTriggerMinimumIntervalInTicks` 默认是 **20**（只有触发块里**全是** Forge Energy 时才允许 1）。编辑器会在允许范围内找**最小的、能让 `r × N` 成为整数**的 N，这样平均速率是精确的，而不是四舍五入的结果：

| 你填的速率 | 生成的程序 | 说明 |
|---|---|---|
| `1` 物品/tick | `EVERY 20 TICKS` + `INPUT 20` | 最常见的整数情况 |
| `1.5` 物品/tick | `EVERY 20 TICKS` + `INPUT 30` | 20 × 1.5 = 30，精确 |
| `0.001` 物品/tick | `EVERY 1000 TICKS` + `INPUT 1` | 自动拉长间隔以保持精确 |
| `2000` FE/tick | `EVERY 1 TICKS` + `INPUT 2000 sfm:forge_energy:forge:energy` | 纯电力享受 1 tick 特例 |

如果某个速率在允许范围内**无法**精确表示（例如 `1/3` 物品/tick），编辑器会取最近的整数并明确告诉你实际速率是多少（检查器和预览里标「近似」）。速率输入框接受的位数没有限制。

两个和电力有关的硬限制要知道：SFM 内部把能量当 `int` 处理，所以**单次传输上限是 2147483647 FE**，编辑器会自动夹住并警告；另外单个能量方块自身每 tick 能收多少也由它自己决定。

**每条连线生成一个独立的触发块**，这不是偷懒：SFM 在一次触发执行里会把 `INPUT` 的限额**共享给同一触发块内所有 `OUTPUT` 语句**。拆开之后，每条连线有自己的间隔、自己的限额，互不抢。

---

## 面（输入/输出方向）

选中连线后点选的就是**目标方块自己的面**：

- **绝对方位** `上 / 下 / 北 / 南 / 东 / 西`：世界方向，不看方块朝哪。
- **相对方位** `前 / 后 / 左 / 右`：跟随方块自身朝向。方块没有朝向属性时这几个面不会生效，编辑器会在检查器和节点上提示。
- **不限**：不写面，让方块自己判断（多数机器可以直接用，SFM 里叫 unsided）。
- **所有面**：`EACH SIDE`，六个方向逐个尝试，最灵活但查询量最大。

多选会写成 `TOP, NORTH SIDE`。槽位同理，`SLOTS 0-3,7` 是**在解析出来的那个面/能力上的槽位号**，所以同一个方块的不同面可以给不同槽位。

SFM 语法的顺序是「标签 → 轮流 → 面 → 槽位」，编辑器按这个顺序输出，你不用记。

---

## 图谱存在哪里

**存在程序里。** 生成的程序除了语句，还有一段注释：

```
-- @sfmgraph:v1 BEGIN
-- eNqNk11v2yAYhf8Ll1WowMY2+C6brDYXaabEu5imKMJ9pKgO3ly8Lar63weJEsdem/XGAt5jeM574AX8AjmeAMt3CuRgq6xquVMS+KVGqmeQf38B
-- ...
-- @sfmgraph:v1 END
```

内容是 gzip + base64 的图谱 JSON。SFML 把 `--` 之后当注释，所以程序本身依然是完整可用的纯文本。这么做的好处是图谱**跟着磁盘走**：复制磁盘、把磁盘插到另一个管理器、把程序分享给别人，图谱都在。

几点要注意：

- 打开硬盘里**手工写的**（不是图谱生成的）程序时，编辑器会尽量从 `FROM xxx` / `TO xxx` 里认出标签、生成对应的孤立节点，方便你接着画。
- 这种程序在第一次保存时会**弹窗确认**，因为保存等于用图谱重新生成整个程序。想留底就先 `文件 → 复制原程序`。
- 程序文本有长度上限（SFM 的 `Program.MAX_PROGRAM_LENGTH`，约 32k 字符），状态栏右下角实时显示用量，超了会在保存前拦住你。图谱数据块本身很小（几十条连线也就几百字节）。

---

## 从源码构建

需要 **JDK 21**。本机没装的话，Minecraft 启动器自带的 `%APPDATA%\.minecraft\runtime\java-runtime-delta` 就是一个完整 JDK。

```bat
:: Windows，双击或命令行都行
build.bat build        :: 找 JDK、跑 gradlew、产出 jar
run_client.bat         :: 启动带 SFM 的开发客户端
```

或者直接用 wrapper：

```bash
./gradlew build        # 产物在 build/libs/sfmgraph-MC1.21.1-1.0.0.jar
./gradlew test         # 96 个单元测试
./gradlew runClient    # 开发客户端（首次会下载并反编译 Minecraft，几分钟）
```

`gradle.properties` 里可调：`minecraft_version`、`neo_version`、`sfm_version`、`sfm_version_id`、`mod_version`。

### ⚠ 换 SFM 版本时的坑

`build.gradle` 依赖的是 **Modrinth 的 version id**（现在是 `sfm_version_id=lthZRkjn`，对应 4.32.0）而不是版本号。原因是 SFM 在多个 Minecraft 版本上复用同一个版本号——比如 `4.34.0` 同时存在于 1.21.1、1.20.1 和 26.1.2，用版本号解析会**静默**挑中 1.20.1 的 Forge 版，然后在编译期报一堆莫名其妙的 `cannot access ForgeConfigSpec`。要换 SFM 版本，去 <https://api.modrinth.com/v2/project/super-factory-manager/version> 找 1.21.1 + neoforge 那个文件的 `id` 填进去。

选 4.32.0 而不是更新的版本，是因为**构建就对着这个版本编译**，而它同时是声明的最低支持版本——这样代码不可能不小心用到后面版本才有的 API，装了这个 mod 的人用 4.32 到最新版都能跑。

---

## 已知限制

- **界面是中文的**，没有做多语言（`assets/sfmgraph/lang/` 里只有入口文案的 en_us）。生成的程序注释也是中文。
- **界面只在一个存档、一个 GUI 缩放下跑过**：构建环境里没有安装 Minecraft 本体，界面交互是在别处过了一遍的——一个存档、一个 GUI 缩放，然后修掉了画布坐标那个 bug（见下面的验证情况）。多人服务器下的实际搬运效果没有验证。
- **只支持物品、流体、Forge Energy**。Mekanism 的化学品（`chemical`、`gas`、`slurry`…）SFM 是支持的，但这个编辑器还没暴露出来。
- **流体过滤器不能是 SFML 关键字**（比如叫 `in` 的流体）：流体 ID 没有可靠的加引号写法，编辑器会直接报错而不是生成一个可能解析失败的程序。物品过滤器没这个问题（会自动加引号）。
- **SFM 的服务器配置会影响生成的间隔**：编辑器会读 `timerTriggerMinimumIntervalInTicks`（读不到就用默认 20/1），但如果你在一个把该值调大的服务器上玩、而配置又没同步到客户端，生成的程序可能偏短——保存前的 SFM 编译器校验会当场报出来。
- 图谱数据块是 base64，不要手工改；语句部分可以随便改（但下次从图谱保存会覆盖）。

---

## 验证情况

已经做到的：

- **对照真实的 SFM 4.32.0 jar 编译通过**，主源码零错误；用到的每个 API（编辑器注册表、配置读取、程序编译校验、标签表、保存通道）都已确认在 4.32.0 里存在。
- **96 个单元测试全绿**（55 个语句生成与速率换算 + 12 个图谱持久化 + 23 个 SFM 真实编译器验证 + 6 个画布坐标），其中那 23 个会把生成的程序交给 **SFM 自己的 ANTLR 解析器 + AST 构建器**验证——这一步不是形式主义，它抓出了两个真实缺陷：
  1. 标签或过滤器与 SFML 关键字撞名时（例如把方块标成 `in`、`output`），SFM 解析失败。SFM 自己的 `Label.needsQuotes` 规则也漏掉这种情况；现在编辑器会加引号，并且有测试证明 SFM 接受。
  2. 单段流体 ID 被写成了 `fluid:water`，那是**两段**、SFM 会当成物品 ID 读；正确写法是 `fluid::water`（三段，命名空间为空）。
- 速率换算有独立测试覆盖：整数、小数、`0.001` 这种极小速率、超上限的电力、非法输入、被禁用的连线等。
- **画布坐标有独立测试**（`CanvasViewTest`）：断言绘制用的变换矩阵和鼠标换算函数对同一个点给出同一个屏幕坐标。这条测试是为了一个已经发生过的真实 bug 而写的——平移量曾经在绘制时被缩放、在鼠标换算时没有，导致平移或缩放过之后点击位置全偏。
- **jar 打包正确**（`neoforge.mods.toml` + lang 文件就位）。

没有验证的：多人服务器下的实际搬运效果。（界面交互已在实际游戏里跑通并修掉了上面那条坐标 bug，但只测过一个存档、一个 GUI 缩放。）

---

## 代码结构

```
sfmgraph/
├─ build.gradle                     ModDevGradle + SFM 依赖 + 测试专用 classpath
├─ gradle.properties                版本号集中在这里
├─ build.bat / run_client.bat       找 JDK 并跑 wrapper
└─ src/
   ├─ main/java/dev/sfmgraph/
   │  ├─ SFMGraph.java                      模组入口（只在客户端做注册）
   │  ├─ graph/                             ← 与 Minecraft 无关，可单测
   │  │  ├─ GraphModel / GraphNode / GraphLink    图谱数据
   │  │  ├─ SideSet / GraphSide            面的选择与 SFML 限定词
   │  │  ├─ ResourceSpec / ResourceKind    资源类型、ID 校验与加引号
   │  │  ├─ SfmlKeywords                   SFML 关键字表（避免标签撞名）
   │  │  ├─ SfmlCompiler                   图谱 → SFML 程序（核心）
   │  │  ├─ GraphCodec                     图谱 ↔ 程序内注释块
   │  │  └─ TriggerMode / RoundRobinMode / LinkCondition
   │  └─ client/
   │     ├─ GraphEditorScreen              主界面：布局、输入、菜单、弹窗、保存
   │     ├─ Inspector                      右侧检查器的控件装配
   │     ├─ LabelFacts                     从磁盘标签表读方块信息（客户端）
   │     ├─ GraphTextEditorRegistration    接入 SFM 的文本编辑器注册表
   │     ├─ SFMGraphClient                 注册 + Ctrl+G 键路径
   │     └─ ui/                            Theme / Draw / CanvasView / Elements / MenuBar / Modal
   └─ test/java/dev/sfmgraph/
      ├─ graph/SfmlCompilerTest             语句生成与速率换算
      ├─ graph/GraphCodecTest               图谱持久化与程序导入
      ├─ graph/GeneratedProgramIsValidSfmlTest  用 SFM 真实解析器验证生成结果
      └─ client/ui/CanvasViewTest           画布变换与鼠标坐标必须一致
```

设计上想强调的两点：**`graph/` 包里没有任何 Minecraft import**，所以核心逻辑可以脱离游戏测试，也是它能在测试里直接喂给 SFM 解析器的原因；**界面没有 mixin、没有自定义网络包、没有方块物品**，只用了 SFM 自己设计的编辑器注册表（`sfm:text_editor`）和它自己的保存通道，所以它只会随着 SFM 更新而失效，不会跟别的东西抢。

---

## 许可

MIT。Super Factory Manager 由 TeamDman 开发，采用 MPL-2.0，本项目只依赖它、不包含它的代码。
