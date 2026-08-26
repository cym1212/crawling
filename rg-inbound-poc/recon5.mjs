// 정찰 5: 2단계에서 국내배송 선택 → 수량/출고지 입력 → 3단계(배송 구성) 구조 덤프
// 임시저장/제출 없음. 실패해도 각 지점 덤프를 남기고 종료.
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function dumpStructure(page, name) {
  await page.screenshot({ path: `${DUMP}/${name}.png`, fullPage: true }).catch(() => {});
  const info = await page.evaluate(() => {
    const pick = (el) => ({
      tag: el.tagName.toLowerCase(),
      id: el.id || undefined,
      name: el.getAttribute('name') || undefined,
      type: el.getAttribute('type') || undefined,
      placeholder: el.getAttribute('placeholder') || undefined,
      cls: (el.className && String(el.className).slice(0, 70)) || undefined,
      text: (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 60) || undefined,
      visible: !!(el.offsetWidth || el.offsetHeight),
    });
    return {
      url: location.href,
      inputs: [...document.querySelectorAll('input, select, textarea')].map(pick).filter((i) => i.visible),
      buttons: [...document.querySelectorAll('button, [role=button]')].map(pick).filter((b) => b.visible),
      headings: [...document.querySelectorAll('h1,h2,h3,h4,label,th,[class*=title]')].map((e) => (e.innerText || '').trim().replace(/\s+/g, ' ')).filter(Boolean).slice(0, 100),
    };
  }).catch(() => ({ url: page.url(), error: 'evaluate failed' }));
  fs.writeFileSync(`${DUMP}/${name}.json`, JSON.stringify(info, null, 2));
  fs.writeFileSync(`${DUMP}/${name}.html`, await page.content().catch(() => ''));
  return info;
}

// 커스텀 UI 대응 클릭: 일반 클릭 실패 시 JS 클릭
async function clickSafe(page, locator, desc) {
  try {
    await locator.click({ timeout: 5_000 });
  } catch {
    log(desc, '- 일반 클릭 실패 → JS 클릭');
    await locator.evaluate((el) => el.click());
  }
  await page.waitForTimeout(1_200);
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
const removed = await page.evaluate(() => {
  const m = document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]');
  m.forEach((el) => el.remove());
  return m.length;
});
log('코치마크 제거:', removed);

// 1단계: 상품 체크 → 다음
const cb = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
const cbId = await cb.getAttribute('id');
await clickSafe(page, cb, '상품 체크');
log('상품 체크:', cbId, '=', await cb.isChecked());
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(6_000);

// 2단계: 국내배송 선택
const domestic = page.locator('#shipping-classification-domestic');
if ((await domestic.count()) > 0) {
  await clickSafe(page, domestic, '국내배송 라디오');
  log('국내배송 선택됨:', await domestic.isChecked().catch(() => '?'));
}
await page.waitForTimeout(2_000);
await dumpStructure(page, '14-step2-domestic');
log('국내배송 선택 후 덤프 완료');

// 수량 입력 (보이는 수량 입력 필드 전부 1로)
const qtyInputs = page.locator('input[placeholder*="수량"]');
const qtyCount = await qtyInputs.count();
log('수량 입력 필드:', qtyCount, '개 → 각 1 입력');
for (let i = 0; i < qtyCount; i++) {
  const inp = qtyInputs.nth(i);
  if (await inp.isVisible().catch(() => false)) {
    await inp.fill('1').catch((e) => log('수량 입력 실패', i, e.message.slice(0, 50)));
    await page.waitForTimeout(500);
  }
}

// 출고지/회송지: 보이는 select 중 "선택" 상태인 것들 첫 옵션으로 선택 시도
const selects = page.locator('select');
const selCount = await selects.count();
for (let i = 0; i < selCount; i++) {
  const sel = selects.nth(i);
  if (!(await sel.isVisible().catch(() => false))) continue;
  const info = await sel.evaluate((el) => ({
    chosen: el.selectedIndex, options: [...el.options].map((o) => o.text.trim().slice(0, 30)),
  })).catch(() => null);
  if (!info || info.options.length < 2) continue;
  if (info.chosen <= 0) {
    await sel.selectOption({ index: 1 }).catch((e) => log('select 선택 실패', i, e.message.slice(0, 40)));
    log(`select[${i}] → "${info.options[1]}" 선택`);
    await page.waitForTimeout(800);
  }
}
await page.waitForTimeout(1_500);
await dumpStructure(page, '15-step2-filled');

// 다음 → 3단계
const nextBtn = page.locator('button', { hasText: '다음' }).last();
const disabled = await nextBtn.isDisabled().catch(() => false);
log('다음 버튼 비활성 여부:', disabled);
if (!disabled) {
  await nextBtn.click();
  await page.waitForTimeout(8_000);
  const s3 = await dumpStructure(page, '16-wizard-step3');
  log('3단계 덤프 완료. URL:', s3.url);
  log('헤딩:', (s3.headings || []).slice(0, 50).join(' | '));
  log('버튼:', (s3.buttons || []).map((b) => b.text).filter(Boolean).slice(0, 20).join(' | '));
} else {
  log('!!! 다음 비활성 — 15-step2-filled 덤프로 미충족 필드 분석 필요');
}

await ctx.close();
log('종료 (저장 없음).');
