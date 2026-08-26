// 정찰 1단계: WING 로그인 세션 확보 + 홈/메뉴 구조 덤프
// 실행: node recon.mjs  (크롬 창이 뜨면 WING 로그인, 이후 자동 진행)
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const WING = 'https://wing.coupang.com/';

const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome',
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());

log('WING 접속...');
await page.goto(WING, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(3_000);

// 로그인 상태 감지: 로그인 페이지(xauth/login)가 아니게 될 때까지 대기 (최대 5분)
const isLoginPage = () => {
  const u = page.url();
  return u.includes('login') || u.includes('xauth') || u.includes('signin');
};
if (isLoginPage()) {
  log('>>> 로그인 페이지입니다. 뜬 크롬 창에서 WING에 로그인해주세요. (최대 5분 대기)');
  const deadline = Date.now() + 5 * 60_000;
  while (Date.now() < deadline && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) {
    log('!!! 로그인 대기 시간 초과. 종료합니다.');
    await ctx.close();
    process.exit(1);
  }
  await page.waitForTimeout(5_000);
}
log('로그인 상태 확인됨:', page.url());

// 덤프 1: 홈 화면
fs.mkdirSync(DUMP, { recursive: true });
await page.screenshot({ path: `${DUMP}/01-home.png`, fullPage: false });
fs.writeFileSync(`${DUMP}/01-home-url.txt`, page.url() + '\n' + (await page.title()));

// 덤프 2: 페이지 내 모든 링크 + 로켓그로스/입고 관련 요소
const links = await page.evaluate(() => {
  const out = [];
  document.querySelectorAll('a').forEach((a) => {
    const text = (a.innerText || '').trim().replace(/\s+/g, ' ');
    if (text || a.href) out.push({ text: text.slice(0, 60), href: a.getAttribute('href') });
  });
  return out;
});
fs.writeFileSync(`${DUMP}/02-links.json`, JSON.stringify(links, null, 2));

const rgHits = links.filter(
  (l) => /로켓그로스|입고|growth|rocket/i.test(l.text) || /growth|rocket|inbound/i.test(l.href || '')
);
fs.writeFileSync(`${DUMP}/03-rg-links.json`, JSON.stringify(rgHits, null, 2));

// 덤프 3: iframe 유무 (RPA 난이도에 중요)
const frames = ctx.pages().flatMap((p) => p.frames().map((f) => ({ url: f.url(), name: f.name() })));
fs.writeFileSync(`${DUMP}/04-frames.json`, JSON.stringify(frames, null, 2));

log('덤프 완료:', rgHits.length, '개의 로켓그로스/입고 관련 링크 발견');
console.log(JSON.stringify(rgHits.slice(0, 20), null, 2));

await ctx.close();
log('종료. dump/ 폴더 확인.');
