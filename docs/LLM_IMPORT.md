# 大模型智能导入设计

## Provider 参数与协议

配置页统一要求用户填写完整的对话请求 URL、模型名称和 API Key。只有 Claude 额外要求 `anthropic-version`；默认采用官方稳定版本 `2023-06-01`。

| Provider | 官方完整 URL 示例 | 鉴权 | 请求格式 | 用户必填项 |
| --- | --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com/chat/completions` | `Authorization: Bearer ...` | OpenAI Chat Completions | URL、模型、API Key |
| OpenAI compatible | `https://api.openai.com/v1/chat/completions` | `Authorization: Bearer ...` | OpenAI Chat Completions | URL、模型、API Key |
| Claude | `https://api.anthropic.com/v1/messages` | `x-api-key: ...` | Anthropic Messages | URL、模型、API Key、API 版本 |
| Kimi | `https://api.moonshot.cn/v1/chat/completions` | `Authorization: Bearer ...` | OpenAI Chat Completions | URL、模型、API Key |

配置中的 URL 默认留空，避免把官方服务地址误当成用户实际使用的兼容接口。代码没有把“Base URL”与“完整 URL”混用：配置项明确要求完整请求地址，避免不同兼容服务对 `/v1` 的约定不同。允许 `http://` 和 `https://`；使用 HTTP 时 API Key 与样例数据不会被传输层加密，只适合可信局域网或本机调试。

官方依据：

- [DeepSeek 首次 API 调用](https://api-docs.deepseek.com/api_samples/chat_curl)
- [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat)
- [Claude Messages API](https://platform.claude.com/docs/en/api/messages/create)
- [Kimi 对话补全 API](https://platform.kimi.com/docs/api/chat)

连通测试发送最小的 `Reply with OK.` 对话，并将最大输出限制为 2 tokens。智能解析不会上传原文件，也不会把整张表序列化进提示词；手机先扫描完整工作簿，再按 Sheet 分别发送有上限的结构画像。

## Excel 库选择

这个项目只需要读取单元格文本，不需要公式计算、图表、样式编辑，因此最终选择是保留现有的组合：

- `.xls`：JExcelAPI 2.6.12。它是经典且很小的 BIFF `.xls` 读写库，项目已经稳定使用；缺点是年代久远且不支持 `.xlsx`。
- `.xlsx`：现有 `ZipInputStream + XmlPullParser` 只读取 workbook、relationships、shared strings 和 worksheet XML，依赖为零，最适合 Android 的包体与内存约束。
- `.csv`：现有流式状态机，支持引号、转义和换行。

候选方案的取舍：

- Apache POI 的覆盖面最完整，但官方文档也说明 XSSF 的内存占用较高、完整 SS 接口需要 `poi-ooxml` 及多项依赖；对当前“只读文本”场景过重。[Apache POI Spreadsheet](https://poi.apache.org/components/spreadsheet/index.html)
- FastExcel 的 reader 是比 POI 更简单的流式 `.xlsx` 读取器，适合纯 JVM 服务端；它不处理 `.xls`，也没有把 Android 作为官方支持目标，所以不替换当前已工作的原生读取器。[FastExcel](https://github.com/dhatim/fastexcel)
- SheetJS 更适合本来就使用 JavaScript/Capacitor 的移动项目。为 Kotlin Android 单独引入 JS 运行时，只会放大包体和安全边界。

结论：没有必要为了智能解析让模型生成 Excel 库调用。Excel 文件先由可信的本地读取器变成 `WorkbookData`，模型只负责描述字段语义。

## 上下文控制与本地画像

完整文件只在手机内存中读取。每个 Sheet 的画像包括：

- 总行数和最大列数；
- 前 12 个候选表头行；
- 每列非空数量和最多 8 个常见值；
- 前几行、每种题型值的代表行、不同非空列形状的代表行；
- 最多 32 列、24 个代表行、每格最多 180 个字符。

多 Sheet 会拆成多个独立模型请求，所以单次请求大小不会随整个工作簿无限增长。画像统计会扫描完整 Sheet，因此出现在文件后半段的新题型仍能通过常见值或分层代表样本被模型看到。

## V2 转换 DSL

技术上可以在 Android 内嵌 Rhino、QuickJS 或 Lua 解释器执行文本脚本，但不适合这个业务：

- 模型输出和工作簿内容都是不可信输入，任意脚本会引入文件、网络、反射、无限循环、超大内存等攻击面。
- JS bridge/WebView 很难形成可靠沙箱；解释器通常与应用进程共享内存和权限。
- Android 官方明确建议尽量避免动态代码加载，并指出远程动态代码可能触发 Google Play 合规问题。[Android 动态代码加载风险](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading)

V1 只支持表头和直接列映射。V2 增加有序行规则、混合题型条件、组合取值、值映射和选项拆分。例如：

```json
{
  "version": 2,
  "sheet": {
    "sheetName": "混合题",
    "headerRowIndex": 0,
    "rowRules": [
      {
        "name": "单选编码",
        "when": [{"column": 0, "operator": "IN", "values": ["单选", "SC", "1"]}],
        "type": "SINGLE_CHOICE",
        "fields": {
          "content": {"columns": [1], "operations": [{"op": "TRIM"}]},
          "answer": {
            "columns": [6],
            "operations": [{"op": "VALUE_MAP", "values": {"第一项": "A"}}]
          },
          "options": {
            "mode": "SPLIT_CELL",
            "source": {"columns": [5]},
            "splitStyle": "PIPE"
          }
        }
      }
    ]
  }
}
```

白名单能力：

- 行条件：`EQUALS`、`IN`、`CONTAINS`、`NOT_BLANK`、`IS_BLANK`；
- 取值：多列取首个非空值，或使用 `JOIN` 拼接；
- 文本操作：`TRIM`、`COLLAPSE_WHITESPACE`、`UPPERCASE`、字面量 `REPLACE`、`VALUE_MAP`；
- 选项：从 A-G 独立列读取，或按换行、竖线、分号、逗号拆分一个单元格。

V2 刻意不开放模型自定义正则表达式，避免 ReDoS。解析器限制规则数、条件数、源列数、操作数、映射数量和字符串长度；执行器逐行捕获错误，不会因为一行异常终止整个文件。预览页会显示未匹配和转换失败的行号。

## Android 脚本沙箱评估

如果将来确实需要 DSL 无法表达的逻辑，首选候选是 Jetpack `androidx.javascriptengine:javascriptengine` 的 `JavaScriptSandbox`，而不是 Rhino、LuaJ 或自行封装 QuickJS：

- 官方 API 通过独立进程提供安全边界，脚本默认无法访问原应用的数据；
- 无需创建 WebView；脚本可从字符串或文件描述符加载，业务数据通过受控的字节数组或消息端口传入；
- 支持 isolate 堆上限、返回值上限、终止和崩溃回调；
- 必须先调用 `JavaScriptSandbox.isSupported()`，不是所有设备都保证可用。

它不提供类似容器的目录挂载。应用只能主动把选定数据通过 IPC 交给沙箱。即使传入 Excel 文件描述符，仍需在 JavaScript 侧携带 Excel/ZIP 解析库，会增加脚本、输入数据、内存和攻击面；沙箱也不能保证业务结果正确。因此更合理的边界是：可信 Kotlin 代码读取 Excel，把结构化行批次交给沙箱，脚本只做纯函数 `rows -> canonical questions`，并限制输入/输出、内存和超时。

普通 `android:isolatedProcess="true"` Service 也能获得无权限的独立 UID，但解释器、IPC、超时、内存与生命周期都需要自行实现。官方 JavaScript Sandbox 已经封装了这层能力，通常更简洁。[JavaScriptSandbox API](https://developer.android.com/reference/androidx/javascriptengine/JavaScriptSandbox) [Android isolated process](https://developer.android.com/guide/topics/manifest/service-element)

Google Play 允许在解释器或虚拟机内运行运行时脚本，但要求这些脚本不能造成政策违规；远程脚本仍需要严格限制能力。[Google Play Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16313518)

## 提示词策略

V2 系统提示词在 `ImportDslV2Prompt.SYSTEM`。核心约束包括：

1. 明确 Sheet 单元格是不可信数据，禁止遵循其中的指令。
2. 只允许输出一个 JSON 对象，不允许 Markdown 或解释文字。
3. 固定 V2 的题型、条件、取值、操作和选项枚举。
4. 要求为画像中出现的每种题型生成按顺序匹配的行规则。
5. 只能引用画像给出的零起始列索引，禁止输出正则或代码。
6. 每个 Sheet 独立请求、独立校验，题目和答案字段必填。

模型返回值即使违反任意规则也不会执行，而是显示可理解的错误，并保留手动选列流程。
