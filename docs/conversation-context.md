# 客服短期上下文

## 流程

LINE Webhook 驗證簽章並去重 → 同商家、同使用者依事件入庫順序處理 →
讀取同聊天室已送出的近期對話 → 理解意圖與補全問題 →
知識庫搜尋／追問／預約入口／取消確認／人工客服 → 保存待送回覆 →
LINE 送出成功 → 標記可供後續上下文使用。

`ConversationContextService` 只讀 `conversation_turns` 中已確認送出的輪次。
識別由 `tenant_id + line_user_id + source_key` 組成；source 區分私聊、群組和 room。
來源不完整時使用單次事件識別，不跨事件沿用上下文。舊 `conversation_messages`
仍保留作為稽核，但不回填歷史內容，因為舊紀錄無法可靠確認聊天室或是否送達。

`ConversationUnderstandingService` 先辨認明確操作，再使用 AI Provider 產生
結構化的 intent、standalone_question、topic、needs_clarification 及 clarification_question。
OpenAI 使用既有模型的 Responses API Structured Outputs，`store=false`；使用者識別仍以
商家限定 HMAC 傳送，不送 LINE User ID。對话資料不是系統指令，不能作為正式商家事實。
參考：[OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)。

模型理解失敗、拒答或回應格式不符時，採離線規則補全或追問。Local Provider 支援
常見中文服務、價格、時間追問與切換；它不具備完整語意理解，正式自由對話需使用
OpenAI Provider。此功能不更換 embedding 模型或文件切塊方式，因此不需要重新索引。

## 設定

| 環境變數 | 預設值 | 用途 |
|---|---|---|
| APP_CONVERSATION_ENABLED | true | 啟用知識詢問的歷史上下文 |
| APP_CONVERSATION_IDLE_MINUTES | 30 | 最近多少分鐘的輪次可用；也是取消確認的有效期限 |
| APP_CONVERSATION_MAX_TURNS | 5 | 最多最近幾輪對話 |
| APP_CONVERSATION_MAX_CHARS | 6000 | 歷史提問、回答及狀態的合計字元上限 |

超過預算時停止往前讀取，不截斷半個輪次。當前訊息另外提供給理解器。
價格、政策每次都由補全後的問題重新檢索商家已發布知識庫；預約狀態仍查正式資料庫。
模型理解多一次請求，會增加延遲與輸入／輸出費用，沒有呼叫外部服務的離線測試不代表
已驗證正式帳號模型可用性或實際回答品質。

客人可輸入「重新開始」「清除對話」「重設對話」清除後續沿用的狀態。
轉人工、進入操作或店家管理後都建立上下文邊界。店家管理登入連結、預約確認 Token
不提供給理解模型。關閉知識上下文不會移除取消預約的安全確認要求。

到期是停止讀取，不是刪除稽核紀錄；資料保存／清除政策需獨立管理。
第一版不將客服對話永久索引為商家知識，也不自動建立長期顧客偏好。

## 預約與取消

「取消要收費嗎」「預約前需要注意什麼」進入知識詢問。
「我要取消預約」列出該顧客的預約；選擇一筆後再顯示「確定取消／保留預約」。
確認必須符合最近已送出的 reservation ID 與隨機 confirmation token；換話題、過期、
換聊天室或不同顧客都不能沿用。真正異動仍由 `BookingManager` 驗證租戶與擁有者。
文字「好」「對」不直接執行取消，模型本身不具備資料庫或預約工具。

轉人工時將最近最多三項已理解的客人問題附在工單與首次店家通知，最多 1000 字，
不把歷史答案當成事實摘要。同一未結工單沿用原通知去重規則，重複要求會更新工單內容，
不反覆推送通知。

## 重試與排序

`event_sequence` 為資料庫生成的入庫序號，避免相同時間戳造成順序不明。
Ready 查詢與 Claim 都阻擋同商家、同顧客尚未完成的前一事件；其他顧客仍可並行。
同顧客不同聊天室保守地共用排序，但各自保留獨立上下文。
RETRY 會阻擋後續事件直到成功或達 FAILED；過期 Claim 仍沿用既有復原流程。

每個 event 僅一份 prepared reply。送出失敗重試時使用已產生內容，避免重複生成、
重複追加對話。Reply 與 fallback Push 各有穩定 Outbox dedupe key；已記錄送出的內容
不重送。LINE 已收到、但本機尚未記錄成功就崩潰的極短不確定窗口仍存在，
這是既有外部 API 傳送不能與本機資料庫共用交易的限制。

## Migration 與部署

新增 `V10__short_term_conversation_context.sql`；V7–V9 編號留給獨立的預約服務修改。
本分支從主線獨立開發，不包含未合併預約功能。部署前必須核對目標資料庫 Flyway 歷史，
把該環境已套用的 V7 等 migration 原樣保留，並協調 V7–V9 與 V10 的合併／部署順序。
不要先部署 V10，再期待 Flyway 預設自動補跑較低版本；不得以刪除歷史、repair 或
停用驗證解決版本缺漏。此功能開發與本機驗證不代表已部署。

## 驗收

- 同主題連續追問、改口、切換主題、回覆追問與缺少前文。
- 商家、顧客、群組隔離，時間及字數上限。
- 未送出回答不可進入上下文；重試僅保留一輪且沿用準備好的答案。
- 同顧客事件排序、失敗重試阻擋、其他顧客並行。
- 取消政策不觸發取消；取消需匹配有效確認。
- Fake OpenAI HTTP 驗證結構化請求、隱私邊界與未完成回應拒用。

測試使用 H2 與 Local/Fake Provider；真實 LINE／OpenAI 呼叫需另行驗證。
