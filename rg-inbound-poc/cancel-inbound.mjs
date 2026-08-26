// 리허설 제출 건 취소: 입고 ID 지정 → 해당 행의 "입고 취소" → 모달의 "입고 취소" 확정
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const TARGET_ID = process.argv[2] || '1095620396553605120';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
const isLoginPage = () => /login|xauth|signin/.test(page.url());

await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(6_000);
if (isLoginPage()) {
  log('>>> 로그인 필요 (5분 대기)');
  const dl = Date.now() + 300_000;
  while (Date.now() < dl && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) { await ctx.close(); process.exit(1); }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6_000);
}

// 최상단 행이 대상 ID인지 확인 후, 첫 번째 "입고 취소" 버튼 클릭
const rowClicked = await page.evaluate((id) => {
  const text = document.body.innerText || '';
  const firstIdMatch = text.match(/입고 ID\s*(\d+)/);
  if (!firstIdMatch) return '목록에 입고 ID 없음';
  if (firstIdMatch[1] !== id) return `최상단 행이 대상 아님 (${firstIdMatch[1]})`;
  const btn = [...document.querySelectorAll('button')].find((b) => (b.innerText || '').trim() === '입고 취소');
  if (!btn) return '취소 버튼 미발견';
  btn.click();
  return '최상단 행 취소 버튼 클릭';
}, TARGET_ID);
log(rowClicked);
await page.waitForTimeout(3_000);
await page.screenshot({ path: './dump/60-cancel-modal.png' }).catch(() => {});

// WING 모달(data-wuic-props*=modal-view, hidden 아님)의 "입고 취소" 확정 버튼 클릭
const confirmed = await page.evaluate(() => {
  const modals = [...document.querySelectorAll('[data-wuic-props*="modal"]')].filter(
    (m) => !m.classList.contains('hidden') && (m.offsetWidth || m.offsetHeight)
  );
  for (const m of modals) {
    const btn = [...m.querySelectorAll('button')].find((b) => (b.innerText || '').trim() === '입고 취소');
    if (btn) { btn.click(); return '모달 확정 클릭'; }
  }
  return '보이는 취소 모달 미발견';
});
log(confirmed);
await page.waitForTimeout(6_000);

// 결과 확인
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6_000);
const status = await page.evaluate((id) => {
  const m = (document.body.innerText || '').match(new RegExp(`입고 ID\\s*${id}[\\s\\S]{0,120}`));
  return m ? m[0].replace(/\s+/g, ' ').slice(0, 150) : '목록에서 미발견';
}, TARGET_ID);
log('대상 건 상태:', status);
await page.screenshot({ path: './dump/61-after-cancel.png', fullPage: true }).catch(() => {});
fs.writeFileSync('./dump/61-after-cancel.txt', status);
await ctx.close();
log('종료');
