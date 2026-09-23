---
name: line-ai-release
description: Manage release branches, pull requests, migrations and Render production releases for Luzhe1002/Line-AI-Bot. Use for this project's release, deployment, rollback or branch-management work.
---

# LINE AI 發布規則

適用儲存庫：Luzhe1002/Line-AI-Bot。先確認 remote，其他專案不自動套用此分支策略。

- 讀取專案 AGENTS.md 與 docs/production-release.md，檢查實際分支、PR、CI、Render 部署設定；文件不是遠端設定已生效的證據。
- 功能分支 PR 進 main；正式發布由同倉庫 main PR 進 production。main 是整合分支，不代表正式版本。已合併功能的後續獨立工作從最新 main 分支；未合併同功能沿用原分支。
- 發布 PR 使用 merge commit 保留兩條長期分支共同歷史；不 squash/rebase production 發布。重用既有發布 PR，可用 scripts/New-Release.ps1 建立。
- production 必須受 PR 與 verify 成功保護；禁止 force push、刪除及繞過檢查。CI 與 Render checksPass 都需要確認實際生效。
- 首次建立或切換 production 前，核對正式部署 commit 與 Flyway 歷史；未知時完成可審查程式碼與測試，明確指出尚待確認的部署資訊，不猜測正式基底。
- 已套用 migration 不刪除、不改 checksum、不 repair、不停用驗證。修正以新 migration 接續；回復應用前確認資料庫相容性。
- 發布管理、建立 PR 或修 UI 的授權，不自動等於合併或部署授權；沿用對話已有的明確授權，不重複確認。
- 提交前依變更執行驗證；推送後核對遠端 SHA，附上 PR。區分本機測試、CI、合併、Render 部署與健康檢查結果，不能以其中一項代替另一項。
