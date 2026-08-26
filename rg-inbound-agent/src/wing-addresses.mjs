import { openNewInbound, removeCoachMarks, clickSafe, log } from './browser.mjs';

/**
 * SYNC_ADDRESSES 작업: 마법사 3단계 회송지 드로어에서 등록 주소 목록을 읽어 서버에 동기화.
 * 아무것도 저장/제출하지 않는다 (임시 진입 후 브라우저 상태만 사용).
 */
export async function runSyncAddressesJob(page, api) {
  await openNewInbound(page);

  // 최소 경로로 3단계까지: 첫 상품 체크 → 수량 1 → 박스 1개 → 다음
  const checkbox = page.locator('input[type=checkbox][id^=checkbox-]:not(#checkbox-all)').first();
  if ((await checkbox.count()) === 0) {
    throw new Error('주소 동기화 실패: 상품 목록이 비어 있습니다');
  }
  for (let attempt = 0; attempt < 3 && !(await checkbox.isChecked().catch(() => false)); attempt++) {
    await removeCoachMarks(page);
    await clickSafe(checkbox, '상품 체크(주소 동기화용)');
    await page.waitForTimeout(1_200);
  }
  await page.locator('button', { hasText: '다음' }).last().click();
  await page.waitForTimeout(6_000);

  const domestic = page.locator('#shipping-classification-domestic');
  await clickSafe(domestic, '국내배송 라디오');
  await page.waitForTimeout(2_500);

  const qtyInputs = page.locator('input[placeholder*="수량"]');
  const count = await qtyInputs.count();
  for (let i = 0; i < count; i++) {
    const input = qtyInputs.nth(i);
    if (await input.isVisible().catch(() => false)) {
      await input.fill('1').catch(() => {});
      await page.waitForTimeout(300);
    }
  }
  const boxRadio = page.locator('div', { hasText: /박스가 몇 개인가요/ })
      .locator('label, input[type=radio]').filter({ hasText: /^1개$/ }).first();
  await boxRadio.click().catch(async () => boxRadio.evaluate((el) => el.click()));
  await page.waitForTimeout(1_500);
  const cardNext = page.locator('button', { hasText: '다음' }).nth(1);
  if ((await cardNext.isVisible().catch(() => false)) && !(await cardNext.isDisabled().catch(() => true))) {
    await cardNext.click().catch(() => {});
    await page.waitForTimeout(4_000);
  }
  await page.locator('button', { hasText: '다음' }).last().click();
  await page.waitForTimeout(9_000);

  // 회송지 드로어 열기 → 주소 행 텍스트 수집
  const trigger = page.locator('text=입력해주세요').first();
  if ((await trigger.count()) === 0 || !(await trigger.isVisible().catch(() => false))) {
    throw new Error('회송지 드로어 트리거를 찾지 못했습니다');
  }
  await trigger.click().catch(() => {});
  await page.waitForTimeout(3_000);

  const addresses = await page.evaluate(() => {
    const drawer = [...document.querySelectorAll('div')]
        .find((el) => (el.innerText || '').includes('회송지 선택') && (el.offsetWidth || el.offsetHeight)
            && el.querySelectorAll('*').length < 400);
    if (!drawer) return [];
    // 주소 행 후보: 시/도 명칭을 포함한 짧은 텍스트 블록
    const texts = [...drawer.querySelectorAll('label, li, [class*=item], [class*=row], div')]
        .map((el) => (el.innerText || '').trim().replace(/\s+/g, ' '))
        .filter((text) => text.length >= 8 && text.length <= 120
            && /(특별시|광역시|도 |시 |군 |구 )/.test(text)
            && !text.includes('회송지 선택') && !text.includes('새 주소'));
    return [...new Set(texts)];
  });

  if (addresses.length === 0) {
    throw new Error('드로어에서 주소를 읽지 못했습니다 (WING에 등록된 회송지가 없거나 셀렉터 확인 필요)');
  }
  // 가장 짧은 형태(중복 포함 블록 제거): 다른 항목을 포함하는 텍스트는 제외
  const minimal = addresses.filter((a) => !addresses.some((b) => b !== a && a.includes(b)));

  await api.syncAddresses(minimal);
  log('주소 동기화 완료:', minimal.length, '건 —', minimal.join(' | '));
  return minimal.length;
}
