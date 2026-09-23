# 正式環境發布

功能分支 → PR → main（整合）→ 發布 PR → production（正式發布）。
main 合併不等於上線。production 只接受同一儲存庫 main 的發布 PR；修錯也先進 main。

## 一次性啟用

1. 在 Render 確認目前部署成功的 commit，以及正式資料庫 Flyway 歷史。將 production 初始化為該 commit；若尚未正式使用，可明確採用已驗證的 main 作為初始基底。
2. production 設定分支保護：必須 PR、verify 成功、解決討論；禁止 force push 與刪除，套用管理員。單人維護不強制另一人核准。
3. 將本次設定 PR 合併 main，再建立 main → production 發布 PR。發布使用 merge commit，不用 squash/rebase，避免兩條長期分支失去共同提交。
4. Render 服務的部署分支改為 production，Auto Deploy 改為 After CI Checks Pass。若由 Blueprint 管理，也確認 Blueprint 同步來源及實際服務設定，不能只憑 render.yaml 判定已切換。
5. 第一次切換前比對實際部署 commit 與遷移。沒有正式資料庫證據時，不執行切換或宣稱已部署。

## 日常發布

執行 `./scripts/New-Release.ps1`（需登入 GitHub CLI），或在 GitHub 建立 base=production、compare=main 的 PR。
腳本重用既有發布 PR，只建立 PR，不合併或部署。審查完整差異、CI、設定及 migration 後，再依使用者的發布授權合併。
production 與 main 的 push、所有 PR 及手動 workflow_dispatch 都會執行 CI。
CI 驗證 production PR 來源及目標分支已存在的 migration 不得改寫或刪除。
verify 包含後端、前端、Compose 及正式容器建置。Render 等 production commit 的檢查通過才部署。

發布後記錄 commit、Render deployment ID、health 檢查與版本標籤。CI 通過不代表部署完成。

## 回復

優先用修正 PR 經 main 推進 production，不 reset 或 force push。資料庫已套用的 migration 原樣保留，修正用新版本。
若要回復應用程式版本，先確認舊應用與目前資料庫相容及實際 Render 版本；不得回退或清空 Flyway 歷史。
不要用 repair、停用驗證或隱藏 checksum 錯誤繞過發布檢查。

## 平台文件

- [Render Blueprint 分支與 checksPass](https://render.com/docs/blueprint-spec)
- [GitHub 分支保護](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches)
