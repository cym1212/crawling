// 주소 동기화 직접 실행 (작업 큐 없이 — 필터 수정 후 재동기화용)
import { loadConfig } from './src/config.mjs';
import { createApi } from './src/api.mjs';
import { launchBrowser, ensureLoggedIn, log } from './src/browser.mjs';
import { runSyncAddressesJob } from './src/wing-addresses.mjs';

const config = loadConfig();
const api = createApi(config);
const { context, page } = await launchBrowser(config);
try {
  if (await ensureLoggedIn(page, api, config)) {
    await runSyncAddressesJob(page, api);
  }
} finally {
  await context.close().catch(() => {});
}
log('종료');
