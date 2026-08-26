// PoC 최종 리허설 v2: 회송지 선택 + 도착예정일 8/30 이후로 변경 → 제출 → 정보/문서 회수 → 취소
// 안전장치: 날짜가 2026-08-30 이후로 확인되지 않으면 제출하지 않고 종료.
import { chromium } from 'playwright';
import fs from 'fs';

const PROFILE = './wing-profile';
const DUMP = './dump';
const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';
const MIN_DATE = '2026-08-30';
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
const hookDownloads = (p) => p.on('download', async (d) => {
  const fp = `${DUMP}/download-${downloads.length}-${d.suggestedFilename()}`;
  downloads.push(fp);
  await d.saveAs(fp).catch(() => {});
  log('다운로드:', d.suggestedFilename());
});
hookDownloads(page);
ctx.on('page', (p) => { log('팝업:', p.url().slice(0, 80)); hookDownloads(p); });
page.on('dialog', async (d) => { log('다이얼로그:', d.message().slice(0, 80), '→ 수락'); await d.accept().catch(() => {}); });
const isLoginPage = () => /login|xauth|signin/.test(page.url());

log('접속...');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
await page.waitForTimeout(5_000);
if (isLoginPage()) {
  log('>>> 로그인 필요 (5분 대기)');
  const dl = Date.now() + 300_000;
  while (Date.now() < dl && isLoginPage()) await page.waitForTimeout(3_000);
  if (isLoginPage()) { await ctx.close(); process.exit(1); }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(5_000);
}

// ── 마법사 1~2단계 (검증 완료된 경로) ──
log('입고 생성 진입...');
await page.locator('button, a', { hasText: /새로운 입고 생성/ }).first().click();
await page.waitForTimeout(6_000);
await page.evaluate(() => document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]').forEach((el) => el.remove()));
const cb = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
await cb.waitFor({ state: 'attached', timeout: 30_000 });
for (let i = 0; i < 3 && !(await cb.isChecked().catch(() => false)); i++) {
  await page.evaluate(() => document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]').forEach((el) => el.remove()));
  await cb.click().catch(async () => cb.evaluate((el) => el.click()));
  await page.waitForTimeout(1_500);
}
log('상품 체크 확인:', await cb.isChecked());
const next1 = page.locator('button', { hasText: '다음' }).last();
for (let i = 0; i < 15 && (await next1.isDisabled().catch(() => true)); i++) await page.waitForTimeout(1_000);
await next1.click();
await page.waitForTimeout(6_000);
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

// ── 회송지 선택: 우측 드로어에서 사전 등록 주소 "문형산길 246-9" 선택 → 적용 ──
const RETURN_ADDR = '문형산길 246-9'; // 운영에서는 정책 설정값
const returnTrigger = page.locator('text=입력해주세요').first();
if ((await returnTrigger.count()) > 0 && (await returnTrigger.isVisible().catch(() => false))) {
  log('회송지 드로어 열기...');
  await returnTrigger.click().catch(() => {});
  await page.waitForTimeout(3_000);
  await shot(page, '40-return-addr-modal');
  // 드로어 내 대상 주소 클릭 (텍스트 매칭 → 행/라디오)
  const addrItem = page.locator(`text=${RETURN_ADDR}`).first();
  if ((await addrItem.count()) > 0) {
    await addrItem.click().catch(async () => addrItem.evaluate((el) => el.click()));
    log('주소 선택:', RETURN_ADDR);
    await page.waitForTimeout(1_500);
    // 라디오가 별도로 있으면 행 클릭으로 선택 안 될 수 있어 근처 라디오도 시도
    await page.evaluate((addr) => {
      const t = [...document.querySelectorAll('*')].find((el) => el.children.length === 0 && (el.innerText || '').includes(addr));
      if (t) {
        const row = t.closest('li, tr, [class*=item], [class*=row], label') || t.parentElement;
        const radio = row?.querySelector('input[type=radio]');
        if (radio && !radio.checked) radio.click();
      }
    }, RETURN_ADDR);
    await page.waitForTimeout(1_000);
  } else {
    log('!!! 대상 주소를 드로어에서 못 찾음:', RETURN_ADDR);
  }
  // 드로어의 활성화된 "적용" 버튼 클릭
  const applyBtns = page.locator('button', { hasText: '적용' });
  const an = await applyBtns.count();
  for (let i = an - 1; i >= 0; i--) {
    const b = applyBtns.nth(i);
    if ((await b.isVisible().catch(() => false)) && !(await b.isDisabled().catch(() => true))) {
      await b.click().catch(() => {});
      log('회송지 적용 클릭');
      await page.waitForTimeout(3_000);
      break;
    }
  }
  // 드로어 닫힘 확인
  const drawerOpen = await page.locator('text=회송지 선택').first().isVisible().catch(() => false);
  log('드로어 닫힘 여부:', !drawerOpen);
} else {
  log('회송지 트리거 없음 (이미 선택돼 있을 수 있음)');
}
await shot(page, '41-after-return-addr');

// ── 도착예정일 확인/변경: 8/30 이후 강제 ──
// 도착예정일 input: value가 비어 있으면 placeholder(추천 날짜)가 유효값
const readDate = async () =>
  page.evaluate(() => {
    const inp = document.querySelector('.calendar-content-row input');
    if (!inp) return null;
    const v = (inp.value || '').trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(v)) return v;
    const p = (inp.placeholder || '').trim();
    return /^\d{4}-\d{2}-\d{2}$/.test(p) ? p : null;
  });
let curDate = await readDate();
log('현재 도착예정일:', curDate);
if (!curDate || curDate < MIN_DATE) {
  log('날짜 변경 필요 → 달력 열기');
  const dateTrigger = page.locator('.calendar-content-row input').first();
  await dateTrigger.click({ timeout: 10_000 }).catch(async () =>
    dateTrigger.evaluate((el) => el.click()).catch(() => log('날짜 트리거 클릭 실패'))
  );
  await page.waitForTimeout(2_500);
  await shot(page, '42-calendar-open');
  // 달력에서 30, 31일 순으로 활성화된 셀 클릭 시도
  let picked = null;
  for (const day of ['30', '31']) {
    const cell = page
      .locator('[class*=calendar] td, [class*=calendar] button, [class*=picker] td, [class*=date] td')
      .filter({ hasText: new RegExp(`^${day}$`) })
      .first();
    if ((await cell.count()) > 0 && (await cell.isVisible().catch(() => false))) {
      const cls = (await cell.getAttribute('class').catch(() => '')) || '';
      if (!/disabled|unavailable|off/.test(cls)) {
        await cell.click().catch(() => {});
        await page.waitForTimeout(2_500);
        const after = await readDate();
        if (after && after >= MIN_DATE) { picked = after; break; }
      }
    }
  }
  if (!picked) {
    // 폴백: JS로 달력 셀 전수 탐색
    picked = await page.evaluate((minDate) => {
      const cells = [...document.querySelectorAll('td, [class*=day]')].filter((el) => {
        const t = (el.innerText || '').trim();
        return /^\d{1,2}$/.test(t) && !/disabled|unavailable|off/.test(el.className || '');
      });
      const target = cells.find((el) => ['30', '31'].includes(el.innerText.trim()));
      if (target) { target.click(); return 'clicked-' + target.innerText.trim(); }
      return null;
    }, MIN_DATE);
    await page.waitForTimeout(2_500);
    log('폴백 달력 클릭:', picked);
  }
  // 날짜 변경 시 뜨는 인페이지 확인 모달 처리
  await page.evaluate(() => {
    [...document.querySelectorAll('[class*=modal]:not(.hidden), [role=dialog]')].forEach((m) => {
      if (!(m.offsetWidth || m.offsetHeight)) return;
      const btn = [...m.querySelectorAll('button')].find((b) => /확인/.test(b.innerText || ''));
      if (btn) btn.click();
    });
  }).catch(() => {});
  await page.waitForTimeout(1_500);
  curDate = await readDate();
  log('변경 후 도착예정일:', curDate);
  await shot(page, '43-after-date');
}

// ── 동의 체크 ──
const checked = await page.evaluate(() => {
  const out = [];
  document.querySelectorAll('input[type=checkbox]').forEach((el) => {
    if (!el.checked && !el.id.startsWith('checkbox-')) { el.click(); out.push(el.id || el.name || '?'); }
  });
  return out;
});
log('동의 체크:', JSON.stringify(checked));
await page.waitForTimeout(2_500);
await shot(page, '44-before-submit');

// ── 안전장치: 날짜 조건 + 버튼 활성 확인 후에만 제출 ──
curDate = await readDate();
const submitBtn = page.locator('button', { hasText: '입고 제출하기' }).last();
const canSubmit = !(await submitBtn.isDisabled().catch(() => true));
log(`제출 조건 — 날짜: ${curDate} (기준 ${MIN_DATE} 이후), 버튼 활성: ${canSubmit}`);
if (!curDate || curDate < MIN_DATE) {
  log('!!! 날짜 조건 미충족 — 제출하지 않고 종료. 44 덤프 확인.');
  await ctx.close(); process.exit(0);
}
if (!canSubmit) {
  log('!!! 제출 버튼 여전히 비활성 — 제출하지 않고 종료. 44 덤프 확인.');
  await ctx.close(); process.exit(0);
}

log('*** 입고 제출하기 클릭 ***');
await submitBtn.click();
await page.waitForTimeout(10_000);
await shot(page, '45-submitted');
const submittedText = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 2500));
log('제출 후 텍스트:', submittedText.slice(0, 500));
let inboundId = submittedText.match(/입고\s*ID\s*[:\s]*(\d{10,})/)?.[1];

// ── 목록에서 정보 회수 ──
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(7_000);
await shot(page, '46-list-after-submit');
const topRow = await page.evaluate(() => {
  const m = (document.body.innerText || '').match(/입고 ID\s*(\d+)[\s\S]{0,400}/);
  return m ? m[0].replace(/\s+/g, ' ').slice(0, 400) : null;
});
log('목록 최상단:', topRow);
if (!inboundId && topRow) inboundId = topRow.match(/입고 ID\s*(\d+)/)?.[1];
log('입고 ID:', inboundId);

// ── 문서 인쇄 모달 ──
const printBtn = page.locator('button', { hasText: /바코드\/물류문서 인쇄/ }).first();
if ((await printBtn.count()) > 0) {
  await printBtn.click().catch(() => {});
  await page.waitForTimeout(5_000);
  await shot(page, '47-print-modal');
  const dlBtns = page.locator('button, a', { hasText: /다운로드|PDF|인쇄/ });
  const dn = await dlBtns.count();
  for (let i = 0; i < Math.min(dn, 5); i++) {
    const b = dlBtns.nth(i);
    const txt = ((await b.innerText().catch(() => '')) || '').trim();
    if (/다운로드|PDF/.test(txt) && (await b.isVisible().catch(() => false))) {
      log(`"${txt}" 클릭`);
      await b.click().catch(() => {});
      await page.waitForTimeout(4_000);
    }
  }
  await shot(page, '48-print-after');
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(1_500);
}
log('다운로드 파일:', downloads.length ? downloads.join(', ') : '(없음)');

// ── 입고 취소 ──
log('*** 입고 취소 ***');
await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6_000);
const cancelBtn = page.locator('button', { hasText: /입고 취소/ }).first();
if ((await cancelBtn.count()) === 0) {
  log('!!! 취소 버튼 없음 — 수동 취소 필요');
} else {
  await cancelBtn.click().catch(() => {});
  await page.waitForTimeout(3_000);
  await shot(page, '49-cancel-modal');
  const modal = page.locator('[class*=modal], [class*=Modal], [role=dialog]').last();
  const confirmBtn = modal.locator('button', { hasText: /확인|입고 취소|취소하기|네/ }).last();
  if ((await confirmBtn.count()) > 0) {
    log('취소 확인:', (await confirmBtn.innerText().catch(() => '')).trim());
    await confirmBtn.click().catch(() => {});
    await page.waitForTimeout(5_000);
  }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(6_000);
  await shot(page, '50-after-cancel');
  const after = await page.evaluate(() => {
    const m = (document.body.innerText || '').match(/입고 ID\s*(\d+)[\s\S]{0,150}/);
    return m ? m[0].replace(/\s+/g, ' ').slice(0, 150) : null;
  });
  log('취소 후 최상단:', after);
}

log('=== 리허설 v2 종료 === 입고 ID:', inboundId ?? '미확인');
await ctx.close();
