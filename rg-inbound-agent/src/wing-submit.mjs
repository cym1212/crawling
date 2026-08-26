import path from 'path';
import { openNewInbound, removeCoachMarks, clickSafe, log, LIST_URL } from './browser.mjs';

/**
 * SUBMIT 작업: WING 입고생성 완주 (PoC poc-final2.mjs에서 실증된 경로 이식 + 다중 상품/수량 확장).
 * 성공 시 { wingInboundId, fulfillmentCenter, arrivalDate } 반환, 실패 시 명확한 사유와 함께 throw.
 */
export async function runSubmitJob(page, api, config, job) {
  const { planId, items, boxCount, returnAddress } = job;
  if (!returnAddress) {
    throw new Error('회송지 설정이 비어 있습니다 (서버 설정 확인)');
  }

  await openNewInbound(page);

  // ── 1단계: 각 옵션 ID로 검색해 상품 체크 ──
  for (const item of items) {
    await searchAndCheckOption(page, item.vendorItemId);
  }
  await page.locator('button', { hasText: '다음' }).last().click();
  await page.waitForTimeout(6_000);
  log('1단계 통과 — 상품', items.length, '건');

  // ── 2단계: 국내배송 → 옵션 행 정리 + 수량 → 박스 수 ──
  const domestic = page.locator('#shipping-classification-domestic');
  await clickSafe(domestic, '국내배송 라디오');
  await page.waitForTimeout(2_500);

  await pruneAndFillQuantities(page, items);
  await selectBoxCount(page, boxCount);

  // 입고 카드 내부 "다음" (박스 적용) → 푸터 "다음"
  const cardNext = page.locator('button', { hasText: '다음' }).nth(1);
  if ((await cardNext.isVisible().catch(() => false)) && !(await cardNext.isDisabled().catch(() => true))) {
    await cardNext.click().catch(() => {});
    await page.waitForTimeout(4_000);
  }
  const footerNext = page.locator('button', { hasText: '다음' }).last();
  if (await footerNext.isDisabled().catch(() => true)) {
    throw new Error('2단계 완성 실패 — 다음 버튼 비활성 (수량/박스 입력 확인 필요)');
  }
  await footerNext.click();
  await page.waitForTimeout(9_000);
  log('3단계 도달');

  // ── 3단계: 회송지 → 동의 → 제출 (스마트 재고 배치 + 추천 도착일 기본값 수용) ──
  await selectReturnAddress(page, returnAddress);
  await checkAgreements(page);

  const submitBtn = page.locator('button', { hasText: '입고 제출하기' }).last();
  if (await submitBtn.isDisabled().catch(() => true)) {
    throw new Error('제출 버튼 비활성 — 필수 입력 미충족 (회송지/동의 확인 필요)');
  }
  log('*** 입고 제출하기 클릭 ***');
  await submitBtn.click();
  await page.waitForTimeout(10_000);

  const bodyText = await page.evaluate(() => (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 3_000));
  if (!bodyText.includes('입고 제출이 완료')) {
    throw new Error('제출 완료 화면을 확인하지 못했습니다: ' + bodyText.slice(0, 300));
  }
  const wingInboundId = bodyText.match(/입고 ID\s*(\d{10,})/)?.[1] ?? null;
  const fulfillmentCenter = bodyText.match(/물류센터\s+(\S+)/)?.[1] ?? null;
  const arrivalDate = bodyText.match(/도착예정일\s+(\d{4}-\d{2}-\d{2})/)?.[1] ?? null;
  log('제출 완료 — 입고 ID', wingInboundId, '/ FC', fulfillmentCenter, '/ 도착', arrivalDate);
  if (!wingInboundId) {
    throw new Error('제출은 완료됐으나 입고 ID를 추출하지 못했습니다 (수동 확인 필요)');
  }

  // ── 문서 회수 (실패해도 제출 결과는 유효 — 알림 후 계속) ──
  try {
    await downloadDocuments(page, api, config, planId, wingInboundId);
  } catch (e) {
    log('문서 회수 실패:', e.message);
    await api.notify(`문서(PDF) 자동 회수 실패 — 입고 ID ${wingInboundId}: ${e.message}\nWING 입고관리의 [바코드/물류문서 인쇄]에서 직접 출력해주세요.`);
  }

  return { wingInboundId, fulfillmentCenter, arrivalDate };
}

/** 옵션 ID 검색 → 결과 상품 체크 */
async function searchAndCheckOption(page, vendorItemId) {
  const typeSelect = page.locator('select.search-type-select').first();
  if ((await typeSelect.count()) > 0) {
    await typeSelect.selectOption({ label: '옵션 ID' }).catch(() => {});
  }
  const input = page.locator('input.search-text-input').first();
  await input.fill(String(vendorItemId));
  await input.press('Enter');
  await page.waitForTimeout(4_000);
  await removeCoachMarks(page);

  const checkbox = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
  if ((await checkbox.count()) === 0) {
    throw new Error(`상품 검색 결과 없음: 옵션 ID ${vendorItemId}`);
  }
  for (let attempt = 0; attempt < 3 && !(await checkbox.isChecked().catch(() => false)); attempt++) {
    await clickSafe(checkbox, `상품 체크(${vendorItemId})`);
    await page.waitForTimeout(1_200);
  }
  if (!(await checkbox.isChecked().catch(() => false))) {
    throw new Error(`상품 체크 실패: 옵션 ID ${vendorItemId}`);
  }
}

/**
 * 2단계 옵션 행 정리: 계획에 없는 옵션 행 삭제 + 계획 수량 입력.
 * 상품 체크 시 형제 옵션이 같이 딸려오므로 반드시 정리해야 한다.
 */
async function pruneAndFillQuantities(page, items) {
  const wanted = {};
  for (const item of items) {
    wanted[String(item.vendorItemId)] = item.quantity;
  }

  const result = await page.evaluate((wantedMap) => {
    const report = { removed: 0, filled: 0, unknownRows: 0, missing: [] };
    const findRows = () => {
      const rows = [];
      document.querySelectorAll('input[placeholder*="수량"]').forEach((input) => {
        if (!(input.offsetWidth || input.offsetHeight)) return;
        let node = input.parentElement;
        let optionId = null;
        for (let depth = 0; depth < 10 && node; depth++) {
          const match = (node.innerText || '').match(/\b(\d{10,12})\b/);
          if (match) { optionId = match[1]; break; }
          node = node.parentElement;
        }
        rows.push({ input, row: node, optionId });
      });
      return rows;
    };

    // 1) 계획에 없는 옵션 행 삭제 (행 왼쪽 ✕)
    for (const { row, optionId } of findRows()) {
      if (!optionId) { report.unknownRows++; continue; }
      if (wantedMap[optionId] !== undefined) continue;
      const removeBtn = row
          ? [...row.querySelectorAll('button, i, span, [role=button]')].find((el) => {
              const text = (el.innerText || '').trim();
              const cls = String(el.className || '');
              return text === '×' || text === '✕' || /delete|remove|close/i.test(cls);
            })
          : null;
      if (removeBtn) { removeBtn.click(); report.removed++; }
    }

    // 2) 계획 수량 입력 (네이티브 세터로 Vue 반응성 유발)
    const setValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    for (const { input, optionId } of findRows()) {
      if (!optionId || wantedMap[optionId] === undefined) continue;
      setValue.call(input, String(wantedMap[optionId]));
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      report.filled++;
    }

    // 3) 검증: 남은 행과 계획이 일치하는가
    const remaining = findRows().map((r) => r.optionId).filter(Boolean);
    for (const id of Object.keys(wantedMap)) {
      if (!remaining.includes(id)) report.missing.push(id);
    }
    report.extra = remaining.filter((id) => wantedMap[id] === undefined);
    return report;
  }, wanted);

  await page.waitForTimeout(1_500);
  log('옵션 행 정리:', JSON.stringify(result));
  if (result.missing.length > 0) {
    throw new Error('계획 옵션이 화면에 없습니다: ' + result.missing.join(', '));
  }
  if (result.extra.length > 0) {
    throw new Error('계획에 없는 옵션 행 삭제 실패: ' + result.extra.join(', '));
  }
}

/** "이 입고로 보낼 박스가 몇 개인가요?" — 1개 / 2개 이상(+수량) */
async function selectBoxCount(page, boxCount) {
  const scope = page.locator('div', { hasText: /박스가 몇 개인가요/ });
  const label = boxCount === 1 ? /^1개$/ : /^2개 이상$/;
  const radio = scope.locator('label, input[type=radio]').filter({ hasText: label }).first();
  if ((await radio.count()) === 0) {
    throw new Error('박스 개수 선택 UI를 찾지 못했습니다');
  }
  await radio.click().catch(async () => radio.evaluate((el) => el.click()));
  await page.waitForTimeout(1_500);

  if (boxCount > 1) {
    // "2개 이상" 선택 시 나타나는 박스 수 입력 필드 (실기 검증 필요 지점)
    const countInput = scope.locator('input[type=number], input[placeholder*="박스"]').first();
    if ((await countInput.count()) > 0 && (await countInput.isVisible().catch(() => false))) {
      await countInput.fill(String(boxCount));
      await page.waitForTimeout(1_000);
    } else {
      log('⚠️ 박스 수 입력 필드 미발견 — 2개 이상 선택만 반영됨 (검증 필요)');
    }
  }
}

/** 회송지 드로어에서 설정 주소 선택 (PoC 실증 코드 이식, 공백 무시 매칭) */
async function selectReturnAddress(page, returnAddress) {
  const trigger = page.locator('text=입력해주세요').first();
  if ((await trigger.count()) === 0 || !(await trigger.isVisible().catch(() => false))) {
    log('회송지 트리거 없음 — 이미 선택된 상태로 판단');
    return;
  }
  await trigger.click().catch(() => {});
  await page.waitForTimeout(3_000);

  const normalized = returnAddress.replace(/\s+/g, '');
  const picked = await page.evaluate((norm) => {
    const leaf = [...document.querySelectorAll('*')].find(
        (el) => el.children.length === 0 && (el.innerText || '').replace(/\s+/g, '').includes(norm));
    if (!leaf) return false;
    leaf.click();
    const row = leaf.closest('li, tr, [class*=item], [class*=row], label') || leaf.parentElement;
    const radio = row ? row.querySelector('input[type=radio]') : null;
    if (radio && !radio.checked) radio.click();
    return true;
  }, normalized);
  if (!picked) {
    throw new Error('회송지 주소를 드로어에서 찾지 못했습니다: ' + returnAddress
        + ' (WING 주소 변경 시 30-we에서 회송지를 다시 설정해주세요)');
  }
  await page.waitForTimeout(1_500);

  const applyBtns = page.locator('button', { hasText: '적용' });
  const count = await applyBtns.count();
  for (let i = count - 1; i >= 0; i--) {
    const btn = applyBtns.nth(i);
    if ((await btn.isVisible().catch(() => false)) && !(await btn.isDisabled().catch(() => true))) {
      await btn.click().catch(() => {});
      await page.waitForTimeout(3_000);
      break;
    }
  }
  const drawerOpen = await page.locator('text=회송지 선택').first().isVisible().catch(() => false);
  if (drawerOpen) {
    throw new Error('회송지 적용 후에도 드로어가 닫히지 않았습니다');
  }
  log('회송지 선택 완료:', returnAddress);
}

/** 서비스 동의 체크박스 전부 체크 (상품 체크박스 제외) — 미체크 시 제출 불가 */
async function checkAgreements(page) {
  const checked = await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('input[type=checkbox]').forEach((el) => {
      if (!el.checked && !el.id.startsWith('checkbox-')) {
        el.click();
        out.push(el.id || el.name || '?');
      }
    });
    return out;
  });
  await page.waitForTimeout(2_000);
  log('동의 체크:', JSON.stringify(checked));
}

/** 목록의 [바코드/물류문서 인쇄] 드롭다운에서 PDF 2종 회수 → 서버 업로드 */
async function downloadDocuments(page, api, config, planId, wingInboundId) {
  await page.goto(LIST_URL, { waitUntil: 'domcontentloaded', timeout: 60_000 });
  await page.waitForTimeout(6_000);

  // 최상단 행이 방금 제출 건인지 확인
  const topId = await page.evaluate(() => (document.body.innerText || '').match(/입고 ID\s*(\d+)/)?.[1] ?? null);
  if (topId !== wingInboundId) {
    throw new Error(`목록 최상단이 제출 건이 아닙니다 (최상단: ${topId})`);
  }

  const menus = [
    { menuText: '상품 바코드 인쇄', type: 'barcode' },
    { menuText: '물류 부착문서 인쇄', type: 'attachment' },
  ];
  for (const { menuText, type } of menus) {
    const saved = await captureDocument(page, config, menuText, `plan${planId}-${type}.pdf`);
    await api.uploadDocument(planId, type, saved);
    log('문서 업로드 완료:', type);
  }
}

/** 드롭다운 항목 클릭 → 다운로드/새 탭 PDF 캡처 → 파일 저장 경로 반환 */
async function captureDocument(page, config, menuText, fileName) {
  const target = path.join(config.downloadDir, fileName);

  await page.locator('button', { hasText: /바코드\/물류문서 인쇄/ }).first().click();
  await page.waitForTimeout(2_000);

  const menuItem = page.locator('button, a, li, [role=menuitem]', { hasText: menuText }).first();
  if ((await menuItem.count()) === 0) {
    throw new Error(`인쇄 메뉴 항목을 찾지 못했습니다: ${menuText}`);
  }

  const context = page.context();
  const downloadPromise = page.waitForEvent('download', { timeout: 20_000 }).catch(() => null);
  const popupPromise = context.waitForEvent('page', { timeout: 20_000 }).catch(() => null);
  await menuItem.click();

  const download = await downloadPromise;
  if (download) {
    await download.saveAs(target);
    await page.keyboard.press('Escape').catch(() => {});
    return target;
  }

  const popup = await popupPromise;
  if (popup) {
    // 새 탭 PDF: URL을 세션 쿠키로 직접 받아 저장
    await popup.waitForLoadState('domcontentloaded').catch(() => {});
    const url = popup.url();
    const response = await context.request.get(url);
    if (!response.ok()) {
      throw new Error(`PDF 응답 실패 (${menuText}): HTTP ${response.status()}`);
    }
    const fs = await import('fs');
    fs.writeFileSync(target, await response.body());
    await popup.close().catch(() => {});
    return target;
  }
  throw new Error(`다운로드/새 탭이 감지되지 않았습니다: ${menuText}`);
}
