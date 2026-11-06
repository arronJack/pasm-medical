-- =====================================================================
-- 医师确认结论的来源判据（V5，2026-11-05）
--
-- ★ 为什么需要这一版：
--   P0 合规根因是「预问诊摘要按设计不含医师结论」，从就诊里抽出来的候选
--   正文实际是「患者自述 + 分诊建议」，不具备作为机构回答依据的资格。
--   旧实现只靠「assessment/plan 是否为空」加一句文字护栏来区分 —— **挡不住人**：
--   ① DemoDataSeeder 手工把判断/处置填齐了，演示数据看起来像医师确认；
--   ② 审核台 approve() 只检查状态，超管点一下就能把患者自述写进 knowledge_docs。
--   文字护栏是可以被一次点击绕过的假校验，比不设防更危险。
--
-- ★ 结构性修法：**显式记录就诊来源**，只有坐诊台结束会话时由医师填写的
--   判断/处置才算「医师确认」。候选继承这个判据，approve() 硬拒未确认候选。
--   这样即使有人误把 MEDICAL_LEARNING_AUTO 打开，也只会生成候选，
--   不会把患者自述变成机构回答依据（闸门不依赖开关）。
--
-- encounters.source 取值：
--   AI_PRECONSULT              AI 预问诊结束（合规上不含结论）
--   HUMAN_CONSULT_NO_CONCLUSION 人工坐诊结束但医师未填判断/处置
--   PHYSICIAN_CONFIRMED        人工坐诊结束且医师填写了判断或处置 ★ 唯一合格来源
--   DEMO                       演示种子数据（绝不作为依据）
--   LEGACY                     V5 之前的历史行，**默认不可采纳**（保守）
--
-- ⚠️ dev(H2) 与生产(PG) 共用同一份迁移；已应用的版本永不修改。
-- =====================================================================

ALTER TABLE encounters
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'LEGACY';

ALTER TABLE kb_candidates
    ADD COLUMN physician_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN encounters.source IS
    '就诊来源：AI_PRECONSULT / HUMAN_CONSULT_NO_CONCLUSION / PHYSICIAN_CONFIRMED / DEMO / LEGACY；仅 PHYSICIAN_CONFIRMED 可作自学习依据';
COMMENT ON COLUMN kb_candidates.physician_confirmed IS
    '候选是否源自医师确认后的判断/处置；false 的候选一律不得 approve 进 knowledge_docs';

-- 审核台按「是否合格」筛选用；也便于运维统计误采比例。
CREATE INDEX idx_kb_candidates_confirmed ON kb_candidates (physician_confirmed);
CREATE INDEX idx_encounters_source       ON encounters (source);
