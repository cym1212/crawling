// 정찰 3단계: 상품 1개 체크 → "다음" → 2단계(입고 정보) 구조 덤프 → 저장 없이 종료
// 임시저장/제출 버튼은 절대 누르지 않음. 브라우저를 그냥 닫아 흔적 없이 종료.
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function dumpStructure(page, name) {
  await page.screenshot({ path: `${DUMP}/${name}.png`, fullPage: true });
  const info = await page.evaluate(() => {
    const pick = (el) => ({
      tag: el.tagName.toLowerCase(),
      id: el.id || undefined,
      name: el.getAttribute('name') || undefined,
      type: el.getAttribute('type') || undefined,
      placeholder: el.getAttribute('placeholder') || undefined,
      cls: (el.className && String(el.className).slice(0, 80)) || undefined,
      text: (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 60) || undefined,
      visible: !!(el.offsetWidth || el.offsetHeight),
    });
    return {
      url: location.href,
      radios: [...document.querySelectorAll('input[type=radio]')].map(pick),
      inputs: [...document.querySelectorAll('input, select, textarea')].map(pick).filter((i) => i.visible),
      buttons: [...document.querySelectorAll('button, [role=button]')].map(pick).filter((b) => b.visible),
      headings: [...document.querySelectorAll('h1,h2,h3,h4,label,th')].map((e) => (e.innerText || '').trim()).filter(Boolean).slice(0, 80),
    };
  });
  fs.writeFileSync(`${DUMP}/${name}.json`, JSON.stringify(info, null, 2));
  fs.writeFileSync(`${DUMP}/${name}.html`, await page.content());
  return info;
}

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome',
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
page.on('dialog', async (d) => {
  log('다이얼로그 감지:', d.type(), d.message().slice(0, 80), '→ dismiss');
  await d.dismiss().catch(() => {});
});

const isLoginPage = () => /login|xauth|signin/.test(page.url());

log('입고관리 목록 접속...');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(5_000);
if (isLoginPage()) {
  log('>>> 로그인 필요 — 뜬 창에서 로그인해주세요 (최대 5분)');
  const deadline = Date.now() + 5 * 60_000;
  while (Date.now() < deadline && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) { await ctx.close(); process.exit(1); }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(5_000);
}

log('입고 생성 진입...');
await page.locator('button, a', { hasText: /새로운 입고 생성|입고\s*생성/ }).first().click();
await page.waitForTimeout(6_000);

// 온보딩 코치마크 오버레이 제거 (coach-mark-overlay가 전체 화면 클릭을 가로챔)
const removed = await page.evaluate(() => {
  const marks = document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]');
  marks.forEach((el) => el.remove());
  return marks.length;
});
log('코치마크 오버레이 제거:', removed, '개');
await page.waitForTimeout(1_000);

// 상품 1개 체크 (커스텀 체크박스: 숨겨진 input + 라벨 구조 → 라벨/부모 클릭)
const productCheckbox = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
const cbId = await productCheckbox.getAttribute('id');
log('상품 체크 시도:', cbId);
await productCheckbox.click(); // 오버레이 제거 후엔 input이 직접 클릭을 받음
await page.waitForTimeout(1_500);
if (!(await productCheckbox.isChecked())) {
  log('라벨 클릭으로도 미체크 → JS로 강제 클릭');
  await page.evaluate((id) => {
    const el = document.getElementById(id);
    el.click();
    if (!el.checked) {
      el.checked = true;
      el.dispatchEvent(new Event('change', { bubbles: true }));
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }, cbId);
  await page.waitForTimeout(1_500);
}
log('체크 상태:', await productCheckbox.isChecked());
await page.waitForTimeout(1_000);
await page.screenshot({ path: `${DUMP}/12-step1-checked.png` });

// 하단 "다음" 클릭 (푸터의 마지막 다음 버튼)
log('다음 클릭 → 2단계 진입 시도...');
await page.locator('button', { hasText: '다음' }).last().click();
await page.waitForTimeout(6_000);

const step2 = await dumpStructure(page, '13-wizard-step2');
log('2단계 덤프 완료. URL:', step2.url);
log('라디오:', JSON.stringify(step2.radios.map((r) => ({ id: r.id, name: r.name, text: r.text })).slice(0, 10)));
log('헤딩/라벨:', step2.headings.slice(0, 40).join(' | '));
log('버튼:', step2.buttons.map((b) => b.text).filter(Boolean).slice(0, 15).join(' | '));

// 저장 없이 그냥 브라우저 종료 (임시저장/제출 안 누름)
await ctx.close();
log('종료 (저장 없음). dump/12*, 13* 확인.');
