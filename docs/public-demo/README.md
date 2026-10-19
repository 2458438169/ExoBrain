# 公开演示资料

这里同时存放两个版本，二者用途不同，**旧版本是不可修改的冻结资产**。

| 版本 | 目录/文件 | 状态 | 用途 |
| --- | --- | --- | --- |
| v2-2026-10-19（当前） | `v2-2026-10-19/` | 可演进 | 访客体验与公开知识库的当前版本：三份带着核对日期的短文档、助手提示词与 `manifest.json` |
| v1-2026-10-14（历史） | `01_exobrain_overview.md`、`02_rag_principles.md`、`03_java_backend_faq.md`、`agent-system-prompt.md`、`99_test_questions.md` | 冻结 | 与 `evaluation/corpus/v1-2026-10-14/` 逐字节对应，`evaluation/tests/test_frozen_assets.py` 会校验 |

## 规则

- **不要修改 v1 的三份正文**：它们与冻结语料快照逐字节比较，任何改动都会让离线评测测试失败。
  需要更新的表述写进 v2；新旧版本可以同时存在，旧版本继续服务历史会话与旧引用。
- **只有 `manifest.json` 里列出的正文可以上传到知识库**。提示词、题集、验收材料与开发文档不进入索引。
- **每次改动 v2 正文都要更新 `manifest.json` 的 `sha256` 与 `bytes`**，否则清单与实际文件不一致。
- 公开知识库的切换步骤见 `docs/development/visitor-experience/deliveries/UX-02.md` 的发布清单：
  候选库先导入并验证，再改公开助手绑定的知识库与提示词，最后用新游客复验。

## 当前版本要点

- 三份正文：`01_product_guide.md`（能做什么、能体验什么、边界）、`02_engineering_cases.md`
  （幂等、取消与迟到答复、行锁与租约三个工程案例）、`03_contribution_map.md`（继承 / 新增 / 未验证 / 未做）。
- 助手提示词 `v2-2026-10-19/agent-system-prompt.md`：一句结论 + 至多三个要点，资料不足时明说，
  区分「已实现 / 已部署 / 已验证」。
- 三个推荐问题与两条追问写在 `manifest.json`，前端首页入口与验收题组都以它为准。
