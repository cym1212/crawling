// 디버그: 1단계에서 상품 체크 후 "다음" 버튼들의 실제 상태 덤프
import { loadConfig } from './src/config.mjs';
import { launchBrowser, openNewInbound, ensureProductChecked, log, LIST_URL } from './src/browser.mjs';

const config = loadConfig();
const { context, page } = await launchBrowser(config);
try {
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(5_000);
  await openNewInbound(page);

  const checkbox = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
  await ensureProductChecked(page, checkbox, '상품 체크(디버그)');
  log('체크 상태:', await checkbox.isChecked());
  await page.waitForTimeout(3_000);

  const buttons = await page.evaluate(() => {
    return [...document.querySelectorAll('button')]
        .filter((btn) => (btn.innerText || '').includes('다음'))
        .map((btn) => ({
          text: (btn.innerText || '').trim(),
          disabled: btn.disabled,
          visible: !!(btn.offsetWidth || btn.offsetHeight),
          cls: String(btn.className).slice(0, 60),
          props: btn.getAttribute('data-wuic-props'),
        }));
  });
  console.log('다음 버튼들:', JSON.stringify(buttons, null, 2));

  const selectedInfo = await page.evaluate(() => {
    const text = (document.body.innerText || '').replace(/\s+/g, ' ');
    return text.match(/전체 \d+[\s\S]{0,40}?선택/)?.[0] ?? text.slice(0, 200);
  });
  console.log('선택 상태 표시:', selectedInfo);

  await page.screenshot({ path: 'downloads/debug-step1.png', fullPage: true });
  log('스크린샷: downloads/debug-step1.png');
} finally {
  await context.close().catch(() => {});
}
