// 정찰 4: 입고관리 목록에서 정찰 중 생긴 draft(작성중/임시저장) 존재 여부 확인 (읽기 전용)
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

const ctx = await chromium.launchPersistentContext(PROFILE, {
  channel: 'chrome',
  headless: false,
  viewport: null,
  args: ['--disable-blink-features=AutomationControlled'],
});
const page = ctx.pages()[0] ?? (await ctx.newPage());
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(6_000);

const rows = await page.evaluate(() => {
  // 목록의 각 입고 건 블록 텍스트를 통째로 수집
  const cands = document.querySelectorAll('table tbody tr, [class*="inbound-item"], [class*="list-item"], [class*="card"]');
  return [...cands]
    .map((el) => (el.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 200))
    .filter((t) => t.length > 10);
});
fs.writeFileSync('./dump/20-list-rows.json', JSON.stringify(rows, null, 2));
await page.screenshot({ path: './dump/20-list-after.png', fullPage: true });

const drafts = rows.filter((t) => /작성\s*중|임시\s*저장|미완료|작성중/.test(t));
log('전체 행:', rows.length, '/ draft 의심 행:', drafts.length);
drafts.forEach((d) => console.log('DRAFT?:', d.slice(0, 150)));
console.log('--- 상위 5행 ---');
rows.slice(0, 5).forEach((r) => console.log(r.slice(0, 150)));

await ctx.close();
log('종료 (읽기 전용).');
