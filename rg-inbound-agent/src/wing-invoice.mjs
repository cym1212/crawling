import { log, LIST_URL } from './browser.mjs';

/**
 * REGISTER_INVOICE 작업: 목록의 [운송장번호 입력]으로 박스별 송장 등록.
 * 모달 구조는 실기 검증으로 튜닝 예정 — 실패 시 명확한 사유로 throw (서버가 재시도/슬랙 처리).
 */
export async function runInvoiceJob(page, job) {
  const { wingInboundId, invoices } = job;

  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(6_000);

  // 대상 입고 건 행의 [운송장번호 입력] 클릭 (행 매칭: 입고 ID 포함 블록)
  const clicked = await page.evaluate((inboundId) => {
    const buttons = [...document.querySelectorAll('button')]
        .filter((btn) => (btn.innerText || '').includes('운송장번호 입력'));
    for (const btn of buttons) {
      let node = btn;
      for (let depth = 0; depth < 15 && node; depth++) {
        node = node.parentElement;
        if (node && (node.textContent || '').includes('입고 ID')) {
          if ((node.textContent || '').includes(inboundId)) { btn.click(); return true; }
          break; // 다른 행
        }
      }
    }
    return false;
  }, wingInboundId);
  if (!clicked) {
    throw new Error(`목록에서 입고 ID ${wingInboundId}의 [운송장번호 입력] 버튼을 찾지 못했습니다`);
  }
  await page.waitForTimeout(3_000);

  // 모달의 송장 입력 필드에 박스 순서대로 입력
  const sorted = [...invoices].sort((a, b) => a.boxNo - b.boxNo);
  const filled = await page.evaluate((trackingNumbers) => {
    const modal = [...document.querySelectorAll('[data-wuic-props*="modal"], [role=dialog], [class*=modal]')]
        .find((el) => !el.classList.contains('hidden') && (el.offsetWidth || el.offsetHeight)
            && (el.innerText || '').includes('운송장'));
    if (!modal) return { error: '운송장 입력 모달 미발견' };

    const inputs = [...modal.querySelectorAll('input[type=text], input:not([type])')]
        .filter((input) => input.offsetWidth || input.offsetHeight);
    if (inputs.length < trackingNumbers.length) {
      return { error: `입력 필드 부족 (필드 ${inputs.length} / 송장 ${trackingNumbers.length})` };
    }
    const setValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    trackingNumbers.forEach((trackingNumber, index) => {
      setValue.call(inputs[index], trackingNumber);
      inputs[index].dispatchEvent(new Event('input', { bubbles: true }));
      inputs[index].dispatchEvent(new Event('change', { bubbles: true }));
    });
    return { ok: true, fields: inputs.length };
  }, sorted.map((invoice) => invoice.trackingNumber));

  if (filled.error) {
    throw new Error('송장 입력 실패: ' + filled.error);
  }
  await page.waitForTimeout(1_500);
  log('송장 입력 완료:', sorted.length, '건 — 저장 버튼 클릭');

  // 모달의 저장/확인/등록 버튼
  const saved = await page.evaluate(() => {
    const modal = [...document.querySelectorAll('[data-wuic-props*="modal"], [role=dialog], [class*=modal]')]
        .find((el) => !el.classList.contains('hidden') && (el.offsetWidth || el.offsetHeight)
            && (el.innerText || '').includes('운송장'));
    if (!modal) return false;
    const button = [...modal.querySelectorAll('button')].reverse()
        .find((btn) => /저장|확인|등록/.test((btn.innerText || '').trim()) && !btn.disabled);
    if (!button) return false;
    button.click();
    return true;
  });
  if (!saved) {
    throw new Error('송장 모달의 저장 버튼을 찾지 못했습니다');
  }
  await page.waitForTimeout(4_000);

  // 모달이 닫혔으면 성공으로 판단
  const stillOpen = await page.evaluate(() =>
      [...document.querySelectorAll('[data-wuic-props*="modal"], [role=dialog], [class*=modal]')]
          .some((el) => !el.classList.contains('hidden') && (el.offsetWidth || el.offsetHeight)
              && (el.innerText || '').includes('운송장')));
  if (stillOpen) {
    throw new Error('송장 저장 후에도 모달이 열려 있습니다 (유효성 오류 가능성)');
  }
  log('송장 등록 완료 — 입고 ID', wingInboundId);
}
