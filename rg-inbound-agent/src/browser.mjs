import { chromium } from 'playwright';

export const LIST_URL = 'https://wing.coupang.com/tenants/rfm-inbound/inbound/list';

export const log = (...args) => console.log(new Date().toISOString().slice(11, 19), ...args);

const isLoginUrl = (url) => /login|xauth|signin/.test(url);

/** 전용 프로필로 브라우저 기동 (실제 크롬, 봇 표식 제거) */
export async function launchBrowser(config) {
  const context = await chromium.launchPersistentContext(config.profileDir, {
    channel: 'chrome',
    headless: false,
    viewport: null,
    acceptDownloads: true,
    args: ['--disable-blink-features=AutomationControlled'],
  });
  const page = context.pages()[0] ?? (await context.newPage());
  page.on('dialog', async (dialog) => {
    log('다이얼로그:', dialog.type(), dialog.message().slice(0, 80), '→ 수락');
    await dialog.accept().catch(() => {});
  });
  return { context, page };
}

/**
 * 입고관리 목록으로 이동하며 로그인 상태 확보.
 * 세션이 죽어 있으면 슬랙(서버 릴레이)으로 1회 알리고 사람 로그인을 대기한다.
 */
export async function ensureLoggedIn(page, api, config) {
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(5_000);
  if (!isLoginUrl(page.url())) {
    return true;
  }

  log('세션 만료 — 로그인 대기');
  await api.notify('WING 세션이 만료되었습니다. 담당자 PC에 열린 크롬 창에서 로그인해주세요. (로그인하면 자동으로 이어서 진행됩니다)');
  const deadline = Date.now() + config.loginWaitMinutes * 60_000;
  while (Date.now() < deadline && isLoginUrl(page.url())) {
    await page.waitForTimeout(5_000);
  }
  if (isLoginUrl(page.url())) {
    await api.notify(`로그인 대기 시간(${config.loginWaitMinutes}분)이 지났습니다. 작업은 다음 주기에 재시도됩니다.`);
    return false;
  }
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(5_000);
  log('로그인 확인:', page.url());
  return true;
}

/** 온보딩 코치마크 오버레이 제거 (클릭 가로채기 방지) */
export async function removeCoachMarks(page) {
  await page.evaluate(() => {
    document.querySelectorAll('[class*="coach-mark"], [class*="coachmark"]').forEach((el) => el.remove());
  }).catch(() => {});
}

/** 입고 생성 마법사 진입 */
export async function openNewInbound(page) {
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(5_000);
  await page.locator('button, a', { hasText: /새로운 입고 생성/ }).first().click();
  await page.waitForTimeout(6_000);
  await removeCoachMarks(page);
}

/** 클릭 실패 시 JS 클릭 폴백 */
export async function clickSafe(locator, description) {
  try {
    await locator.click({ timeout: 8_000 });
  } catch {
    log(description, '- 일반 클릭 실패 → JS 클릭');
    await locator.evaluate((el) => el.click());
  }
}

/** 버튼이 활성화될 때까지 대기 (기본 20초) — 미활성이면 false */
export async function waitForEnabled(page, locator, seconds = 20) {
  for (let i = 0; i < seconds; i++) {
    if ((await locator.isVisible().catch(() => false)) && !(await locator.isDisabled().catch(() => true))) {
      return true;
    }
    await page.waitForTimeout(1_000);
  }
  return false;
}

/** 상품 체크박스를 확실히 체크 (attached 대기 + 재시도 + 검증) — PoC 검증 로직 */
export async function ensureProductChecked(page, checkbox, description) {
  await checkbox.waitFor({ state: 'attached', timeout: 30_000 });
  for (let attempt = 0; attempt < 4 && !(await checkbox.isChecked().catch(() => false)); attempt++) {
    await removeCoachMarks(page);
    await clickSafe(checkbox, description);
    await page.waitForTimeout(1_500);
  }
  if (!(await checkbox.isChecked().catch(() => false))) {
    throw new Error(description + ' 실패 — 체크박스가 체크되지 않습니다');
  }
}
