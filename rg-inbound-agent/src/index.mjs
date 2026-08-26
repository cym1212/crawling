// 로켓그로스 입고 RPA 에이전트 — 메인 루프
// 사용법: node src/index.mjs [--once]
//   평시: pollIntervalSec 주기로 서버 작업 큐를 확인해 WING을 조작한다.
//   --once: 한 사이클만 실행하고 종료 (수동 실행/테스트용).
import { loadConfig } from './config.mjs';
import { createApi } from './api.mjs';
import { launchBrowser, ensureLoggedIn, log } from './browser.mjs';
import { runSubmitJob } from './wing-submit.mjs';
import { runInvoiceJob } from './wing-invoice.mjs';
import { runSyncAddressesJob } from './wing-addresses.mjs';

const config = loadConfig();
const api = createApi(config);
const runOnce = process.argv.includes('--once');

async function processJobs(jobs) {
  const { context, page } = await launchBrowser(config);
  try {
    if (!(await ensureLoggedIn(page, api, config))) {
      return; // 로그인 대기 초과 — 다음 주기에 재시도
    }

    // 순서: 주소 동기화 → 제출 → 송장 등록
    const order = { SYNC_ADDRESSES: 0, SUBMIT: 1, REGISTER_INVOICE: 2 };
    jobs.sort((a, b) => (order[a.type] ?? 9) - (order[b.type] ?? 9));

    for (const job of jobs) {
      log('작업 시작:', job.type, job.planId ?? '');
      try {
        if (job.type === 'SYNC_ADDRESSES') {
          await runSyncAddressesJob(page, api);
        } else if (job.type === 'SUBMIT') {
          await api.startPlan(job.planId);
          try {
            const result = await runSubmitJob(page, api, config, job);
            await api.submitResult(job.planId, { success: true, ...result });
          } catch (e) {
            await api.submitResult(job.planId, { success: false, failReason: e.message.slice(0, 900) });
            throw e;
          }
        } else if (job.type === 'REGISTER_INVOICE') {
          try {
            await runInvoiceJob(page, job);
            await api.invoiceResult(job.planId, { success: true });
          } catch (e) {
            await api.invoiceResult(job.planId, { success: false, failReason: e.message.slice(0, 900) });
            throw e;
          }
        } else {
          log('알 수 없는 작업 유형:', job.type);
        }
        log('작업 완료:', job.type, job.planId ?? '');
      } catch (e) {
        log('작업 실패:', job.type, job.planId ?? '', '-', e.message);
        // 실패 보고는 위에서 완료 — 다음 작업 계속
      }
    }
  } finally {
    await context.close().catch(() => {});
  }
}

async function cycle() {
  let jobs;
  try {
    jobs = await api.getJobs();
  } catch (e) {
    log('서버 연결 실패:', e.message);
    return;
  }
  if (jobs.length === 0) {
    log('대기 중인 작업 없음');
    return;
  }
  log('작업', jobs.length, '건:', jobs.map((job) => job.type + (job.planId ? `#${job.planId}` : '')).join(', '));
  await processJobs(jobs);
}

log('에이전트 시작 — 서버:', config.serverUrl, runOnce ? '(1회 실행)' : `(${config.pollIntervalSec}초 주기)`);
await cycle();
if (!runOnce) {
  setInterval(cycle, config.pollIntervalSec * 1_000);
}
