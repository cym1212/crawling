// 정찰 2단계: 입고관리 목록 → 입고생성 마법사 1단계 구조 덤프 (제출 없음, 읽기만)
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

// 페이지의 조작 가능 요소를 요약 덤프
async function dumpStructure(page, name) {
  await page.screenshot({ path: `${DUMP}/${name}.png`, fullPage: true });
  const info = await page.evaluate(() => {
    const pick = (el) => ({
      tag: el.tagName.toLowerCase(),
      id: el.id || undefined,
      name: el.getAttribute('name') || undefined,
      type: el.getAttribute('type') || undefined,
      placeholder: el.getAttribute('placeholder') || undefined,
      dataTestId: el.getAttribute('data-testid') || el.getAttribute('data-test-id') || undefined,
      cls: (el.className && String(el.className).slice(0, 80)) || undefined,
      text: (el.innerText || el.value || '').trim().replace(/\s+/g, ' ').slice(0, 50) || undefined,
      visible: !!(el.offsetWidth || el.offsetHeight),
    });
    return {
      url: location.href,
      title: document.title,
      framework: {
        react: !!document.querySelector('[data-reactroot], #root, #app'),
        nextData: !!window.__NEXT_DATA__,
      },
      iframes: [...document.querySelectorAll('iframe')].map((f) => ({ src: f.src, id: f.id })),
      inputs: [...document.querySelectorAll('input, select, textarea')].map(pick),
      buttons: [...document.querySelectorAll('button, [role="button"]')].map(pick).filter((b) => b.visible),
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

log('입고관리 목록 접속...');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(5_000);

const isLoginPage = () => {
  const u = page.url();
  return u.includes('login') || u.includes('xauth') || u.includes('signin');
};
if (isLoginPage()) {
  log('>>> 세션 만료 — 뜬 창에서 다시 로그인해주세요. "자동 로그인" 체크박스가 있으면 체크! (최대 5분 대기)');
  const deadline = Date.now() + 5 * 60_000;
  while (Date.now() < deadline && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) {
    log('!!! 로그인 대기 시간 초과. 종료.');
    await ctx.close();
    process.exit(1);
  }
  await page.waitForTimeout(5_000);
  // 로그인 후 목록 페이지로 재이동
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(5_000);
}

const list = await dumpStructure(page, '10-inbound-list');
log('목록 페이지 덤프 완료. 버튼:', list.buttons.map((b) => b.text).filter(Boolean).join(' | '));

// "입고 생성" 버튼 탐색 후 클릭 (마법사 진입은 저장/제출이 아님)
const createBtn = page
  .locator('button, a', { hasText: /입고\s*생성|입고생성/ })
  .first();
if ((await createBtn.count()) === 0) {
  log('!!! 입고 생성 버튼을 못 찾음. 목록 덤프만 저장하고 종료.');
  await ctx.close();
  process.exit(0);
}
log('입고 생성 버튼 클릭...');
await createBtn.click();
await page.waitForTimeout(6_000);

const step1 = await dumpStructure(page, '11-wizard-step1');
log('마법사 1단계 덤프 완료. URL:', step1.url);
log('iframe:', JSON.stringify(step1.iframes));
log('입력필드 수:', step1.inputs.length, '/ 버튼:', step1.buttons.map((b) => b.text).filter(Boolean).slice(0, 15).join(' | '));

await ctx.close();
log('종료. dump/10*, 11* 파일 확인.');
