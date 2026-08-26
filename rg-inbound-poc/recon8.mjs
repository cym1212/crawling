// 정찰 8: 2단계 완성 → 푸터 "다음" 클릭 → 3단계(물류센터/일정 확정) 화면 덤프. 저장 없음.
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function dumpStructure(page, name) {
  await page.screenshot({ path: `${DUMP}/${name}.png`, fullPage: true }).catch(() => {});
  const info = await page.evaluate(() => {
    const vis = (el) => !!(el.offsetWidth || el.offsetHeight);
    const pick = (el) => ({
      tag: el.tagName.toLowerCase(), id: el.id || undefined, name: el.getAttribute('name') || undefined,
      type: el.getAttribute('type') || undefined, placeholder: el.getAttribute('placeholder') || undefined,
      cls: (el.className && String(el.className).slice(0, 70)) || undefined,
      text: (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 70) || undefined,
    });
    return {
      url: location.href,
      visibleText: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 3000),
      inputs: [...document.querySelectorAll('input, select, textarea')].filter(vis).map(pick),
      buttons: [...document.querySelectorAll('button, [role=button]')].filter(vis).map(pick),
    };
  }).catch(() => ({ url: page.url() }));
  fs.writeFileSync(`${DUMP}/${name}.json`, JSON.stringify(info, null, 2));
  fs.writeFileSync(`${DUMP}/${name}.html`, await page.content().catch(() => ''));
  return info;
}

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome', headless: false, viewport: null,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
page.on('dialog', async (d) => { log('다이얼로그:', d.message().slice(0, 60)); await d.dismiss().catch(() => {}); });
const isLoginPage = () => /login|xauth|signin/.test(page.url());

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

log('입고 생성 진입...');
await page.locator('button, a', { hasText: /새로운 입고 생성/ }).first().click();
await page.waitForTimeout(6_000);
await page.evaluate(() => document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]').forEach((el) => el.remove()));

// 1단계: 상품 체크 → 다음
const cb = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
await cb.click().catch(async () => cb.evaluate((el) => el.click()));
await page.waitForTimeout(1_500);
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(6_000);
log('1단계 통과');

// 2단계: 국내배송 → 수량 → 박스 1개 → 카드 다음(적용)
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
// 카드 내부 다음 (있으면)
const cardNext = page.locator('button', { hasText: '다음' }).nth(1);
if ((await cardNext.isVisible().catch(() => false)) && !(await cardNext.isDisabled().catch(() => true))) {
  await cardNext.click().catch(() => {});
  await page.waitForTimeout(4_000);
  log('카드 다음 클릭 (박스 적용)');
}
log('2단계 완성');

// 푸터 "다음" (마지막 다음 버튼) → 3단계
const footerNext = page.locator('button', { hasText: '다음' }).last();
if (await footerNext.isDisabled().catch(() => true)) {
  log('!!! 푸터 다음이 여전히 비활성. 현재 상태 덤프 후 종료.');
  await dumpStructure(page, '19-step2-stuck');
  await ctx.close();
  process.exit(0);
}
log('푸터 다음 클릭 → 3단계 진입...');
await footerNext.click();
await page.waitForTimeout(9_000);

const s3 = await dumpStructure(page, '20-wizard-step3');
log('3단계 덤프 완료. URL:', s3.url);
log('=== 화면 텍스트(앞부분) ===');
console.log((s3.visibleText || '').slice(0, 1200));
log('버튼:', (s3.buttons || []).map((b) => b.text).filter(Boolean).slice(0, 20).join(' | '));

await ctx.close();
log('종료 (저장 없음).');
