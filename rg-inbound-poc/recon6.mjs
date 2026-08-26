// 정찰 6: 2단계 완성(박스 1개 선택) → 3단계(물류센터/일정 확정) 구조 덤프. 저장 없음.
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
      tag: el.tagName.toLowerCase(), id: el.id || undefined, name: el.getAttribute('name') || undefined,
      type: el.getAttribute('type') || undefined, placeholder: el.getAttribute('placeholder') || undefined,
      cls: (el.className && String(el.className).slice(0, 70)) || undefined,
      text: (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 60) || undefined,
      visible: !!(el.offsetWidth || el.offsetHeight),
    });
    return {
      url: location.href,
      radios: [...document.querySelectorAll('input[type=radio]')].map(pick),
      inputs: [...document.querySelectorAll('input, select, textarea')].map(pick).filter((i) => i.visible),
      buttons: [...document.querySelectorAll('button, [role=button]')].map(pick).filter((b) => b.visible),
      headings: [...document.querySelectorAll('h1,h2,h3,h4,label,th,[class*=title]')].map((e) => (e.innerText || '').trim().replace(/\s+/g, ' ')).filter(Boolean).slice(0, 100),
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
log('상품 체크:', await cb.isChecked());
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(6_000);

// 2단계: 국내배송 → 수량 → 박스 1개
const domestic = page.locator('#shipping-classification-domestic');
await domestic.click().catch(async () => domestic.evaluate((el) => el.click()));
await page.waitForTimeout(2_500);

const qtyInputs = page.locator('input[placeholder*="수량"]');
const qn = await qtyInputs.count();
for (let i = 0; i < qn; i++) {
  const inp = qtyInputs.nth(i);
  if (await inp.isVisible().catch(() => false)) { await inp.fill('1').catch(() => {}); await page.waitForTimeout(400); }
}
log('수량 입력 완료:', qn, '개');

// 박스 개수 라디오: "박스가 몇 개인가요" 영역의 "1개" 선택
const boxRadio = page
  .locator('div', { hasText: /박스가 몇 개인가요/ })
  .locator('label, input[type=radio]')
  .filter({ hasText: /^1개$/ })
  .first();
if ((await boxRadio.count()) > 0) {
  await boxRadio.click().catch(async () => boxRadio.evaluate((el) => el.click()));
  log('박스 1개 라디오 클릭 (locator)');
} else {
  // 폴백: 텍스트 '1개'를 가진 라디오/라벨을 JS로 탐색·클릭
  const ok = await page.evaluate(() => {
    const labels = [...document.querySelectorAll('label')].filter((l) => l.innerText.trim() === '1개');
    if (labels.length) { labels[0].click(); return 'label'; }
    const radios = [...document.querySelectorAll('input[type=radio]')].filter((r) => (r.value || '') === '1' || (r.nextElementSibling?.innerText || '').trim() === '1개');
    if (radios.length) { radios[0].click(); return 'radio'; }
    return null;
  });
  log('박스 1개 클릭 (JS 폴백):', ok);
}
await page.waitForTimeout(2_000);
await dumpStructure(page, '17-step2-box1');

// 입고 카드 내부의 "다음 >" → 이후 푸터 "다음"
for (let round = 0; round < 3; round++) {
  const nexts = page.locator('button', { hasText: '다음' });
  const n = await nexts.count();
  let clicked = false;
  for (let i = 0; i < n; i++) {
    const b = nexts.nth(i);
    if ((await b.isVisible().catch(() => false)) && !(await b.isDisabled().catch(() => true))) {
      log(`다음 버튼[${i}/${n}] 클릭 (round ${round})`);
      await b.click().catch(() => {});
      clicked = true;
      await page.waitForTimeout(6_000);
      break;
    }
  }
  if (!clicked) { log('활성화된 다음 버튼 없음 (round', round, ')'); break; }
  const state = await dumpStructure(page, `18-after-next-r${round}`);
  log(`round ${round} 이후 URL:`, state.url);
  const step3Reached = (state.headings || []).some((h) => /물류센터|일정|센터 선택|도착예정/.test(h));
  if (step3Reached) {
    log('*** 3단계(물류센터/일정) 도달! ***');
    log('헤딩:', (state.headings || []).slice(0, 60).join(' | '));
    log('버튼:', (state.buttons || []).map((b) => b.text).filter(Boolean).slice(0, 20).join(' | '));
    break;
  }
}

await ctx.close();
log('종료 (저장 없음).');
