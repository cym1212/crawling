// PoC 최종 리허설: 입고 제출 → 제출 정보 회수 → 문서 인쇄 모달 확인 → 입고 취소
// 실계정에 제출 1건이 생기고 곧바로 취소함. 각 단계 스크린샷/덤프 저장.
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function shot(page, name) {
  await page.screenshot({ path: `${DUMP}/${name}.png`, fullPage: true }).catch(() => {});
  fs.writeFileSync(`${DUMP}/${name}.html`, await page.content().catch(() => ''));
}

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome', headless: false, viewport: null, acceptDownloads: true,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
const downloads = [];
page.on('download', async (d) => {
  const p = `${DUMP}/download-${downloads.length}-${d.suggestedFilename()}`;
  downloads.push(p);
  await d.saveAs(p).catch((e) => log('다운로드 저장 실패:', e.message.slice(0, 60)));
  log('다운로드 감지:', d.suggestedFilename());
});
ctx.on('page', async (p) => {
  log('팝업/새탭 감지:', p.url().slice(0, 100));
  p.on('download', async (d) => {
    const fp = `${DUMP}/download-${downloads.length}-${d.suggestedFilename()}`;
    downloads.push(fp);
    await d.saveAs(fp).catch(() => {});
    log('팝업 다운로드:', d.suggestedFilename());
  });
});
page.on('dialog', async (d) => { log('다이얼로그:', d.type(), '-', d.message().slice(0, 80), '→ 수락'); await d.accept().catch(() => {}); });
const isLoginPage = () => /login|xauth|signin/.test(page.url());

// ── 접속/로그인 ──
log('접속...');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(5_000);
if (isLoginPage()) {
  log('>>> 로그인 필요 — 창에서 로그인해주세요 (5분 대기)');
  const dl = Date.now() + 300_000;
  while (Date.now() < dl && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) { await ctx.close(); process.exit(1); }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(5_000);
}

// ── 마법사: 1단계 ──
log('입고 생성 진입...');
await page.locator('button, a', { hasText: /새로운 입고 생성/ }).first().click();
await page.waitForTimeout(6_000);
await page.evaluate(() => document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]').forEach((el) => el.remove()));
const cb = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
const productId = (await cb.getAttribute('id')).replace('checkbox-', '');
await cb.click().catch(async () => cb.evaluate((el) => el.click()));
await page.waitForTimeout(1_500);
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(6_000);
log('1단계 통과 (상품ID:', productId, ')');

// ── 2단계 ──
const domestic = page.locator('#shipping-classification-domestic');
await domestic.click().catch(async () => domestic.evaluate((el) => el.click()));
await page.waitForTimeout(2_500);
const qtyInputs = page.locator('input[placeholder*="수량"]');
const qn = await qtyInputs.count();
for (let i = 0; i < qn; i++) {
  const inp = qtyInputs.nth(i);
  if (await inp.isVisible().catch(() => false)) { await inp.fill('1').catch(() => {}); await page.waitForTimeout(400); }
}
const boxRadio = page.locator('div', { hasText: /박스가 몇 개인가요/ }).locator('label, input[type=radio]').filter({ hasText: /^1개$/ }).first();
await boxRadio.click().catch(async () => boxRadio.evaluate((el) => el.click()));
await page.waitForTimeout(1_500);
const cardNext = page.locator('button', { hasText: '다음' }).nth(1);
if ((await cardNext.isVisible().catch(() => false)) && !(await cardNext.isDisabled().catch(() => true))) {
  await cardNext.click().catch(() => {});
  await page.waitForTimeout(4_000);
}
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(9_000);
log('3단계 도달');
await shot(page, '30-step3-before');

// ── 3단계: 동의 체크박스 체크 (미체크 + 보이는 것 전부) ──
const checked = await page.evaluate(() => {
  const out = [];
  document.querySelectorAll('input[type=checkbox]').forEach((el) => {
    const vis = !!(el.offsetWidth || el.offsetHeight || el.closest('label'));
    if (vis && !el.checked && !el.id.startsWith('checkbox-')) {
      el.click();
      out.push(el.id || el.name || (el.closest('label')?.innerText || '').slice(0, 40));
    }
  });
  return out;
});
log('동의 체크:', JSON.stringify(checked));
await page.waitForTimeout(2_000);
await shot(page, '31-step3-agreed');

// ── 제출 ──
const submitBtn = page.locator('button', { hasText: '입고 제출하기' }).last();
if (await submitBtn.isDisabled().catch(() => true)) {
  log('!!! 제출 버튼 비활성 — 31 덤프 확인 필요. 종료(제출 안 됨).');
  await ctx.close();
  process.exit(0);
}
log('*** 입고 제출하기 클릭 ***');
await submitBtn.click();
await page.waitForTimeout(10_000);
await shot(page, '32-submitted');
const submittedText = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 2000));
log('제출 후 화면 텍스트:', submittedText.slice(0, 600));
const idMatch = submittedText.match(/입고\s*ID\s*[:\s]*(\d{10,})/);
let inboundId = idMatch?.[1];
log('추출된 입고 ID:', inboundId ?? '(제출 화면에서 미발견 — 목록에서 확인)');

// ── 목록에서 제출 건 정보 회수 ──
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(7_000);
await shot(page, '33-list-after-submit');
const topRow = await page.evaluate(() => {
  const t = (document.body.innerText || '');
  const m = t.match(/입고 ID\s*(\d+)[\s\S]{0,400}/);
  return m ? m[0].replace(/\s+/g, ' ').slice(0, 400) : null;
});
log('목록 최상단 입고 건:', topRow);
if (!inboundId && topRow) inboundId = topRow.match(/입고 ID\s*(\d+)/)?.[1];

// ── 문서 인쇄(바코드/물류문서) 모달 확인 ──
log('바코드/물류문서 인쇄 시도...');
const printBtn = page.locator('button', { hasText: /바코드\/물류문서 인쇄/ }).first();
if ((await printBtn.count()) > 0) {
  await printBtn.click().catch(() => {});
  await page.waitForTimeout(5_000);
  await shot(page, '34-print-modal');
  // 모달 안의 다운로드/인쇄 버튼 시도
  const dlBtns = page.locator('button, a', { hasText: /다운로드|인쇄|출력|PDF/ });
  const dn = await dlBtns.count();
  log('인쇄 모달 내 버튼 후보:', dn, '개');
  for (let i = 0; i < Math.min(dn, 4); i++) {
    const b = dlBtns.nth(i);
    const txt = ((await b.innerText().catch(() => '')) || '').trim();
    if (/다운로드|PDF/.test(txt)) {
      log(`버튼 "${txt}" 클릭`);
      await b.click().catch(() => {});
      await page.waitForTimeout(4_000);
    }
  }
  await shot(page, '35-print-after');
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(1_500);
}
log('다운로드된 파일:', downloads.length ? downloads.join(', ') : '(없음 — 모달 스크린샷으로 확인)');

// ── 입고 취소 ──
log('*** 입고 취소 시도 ***');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6_000);
const cancelBtn = page.locator('button', { hasText: /입고 취소/ }).first();
if ((await cancelBtn.count()) === 0) {
  log('!!! 입고 취소 버튼 없음 — 수동 취소 필요');
} else {
  await cancelBtn.click().catch(() => {});
  await page.waitForTimeout(3_000);
  await shot(page, '36-cancel-modal');
  // 취소 확인 모달의 확정 버튼 (확인/입고 취소/취소하기)
  const confirmBtn = page
    .locator('[class*=modal], [class*=Modal], [role=dialog]')
    .locator('button', { hasText: /확인|입고 취소|취소하기|네/ })
    .last();
  if ((await confirmBtn.count()) > 0) {
    log('취소 확인 버튼 클릭:', (await confirmBtn.innerText().catch(() => '')).trim());
    await confirmBtn.click().catch(() => {});
    await page.waitForTimeout(5_000);
  }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6_000);
  await shot(page, '37-list-after-cancel');
  const afterCancel = await page.evaluate(() => {
    const m = (document.body.innerText || '').match(/입고 ID\s*(\d+)[\s\S]{0,200}/);
    return m ? m[0].replace(/\s+/g, ' ').slice(0, 200) : null;
  });
  log('취소 후 최상단 행:', afterCancel);
}

log('=== 리허설 종료 ===');
log('제출된 입고 ID:', inboundId ?? '미확인(스크린샷 확인)');
await ctx.close();
