import { openNewInbound, clickSafe, log, waitForEnabled, checkAnyValidProductAndNext } from './browser.mjs';

/**
 * SYNC_ADDRESSES 작업: 마법사 3단계 회송지 드로어에서 등록 주소 목록을 읽어 서버에 동기화.
 * 아무것도 저장/제출하지 않는다 (임시 진입 후 브라우저 상태만 사용).
 */
export async function runSyncAddressesJob(page, api) {
  await openNewInbound(page);

  // 최소 경로로 3단계까지: 유효 상품 하나 체크 → 다음 → 수량 1 → 박스 1개 → 다음
  await checkAnyValidProductAndNext(page);

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
  const nextButtons = page.locator('button:visible', { hasText: '다음' });
  if ((await nextButtons.count()) > 1 && !(await nextButtons.first().isDisabled().catch(() => true))) {
    await nextButtons.first().click().catch(() => {}); // 입고 카드 내부 다음 (박스 적용)
    await page.waitForTimeout(4_000);
  }
  const footerNext = page.locator('button:visible', { hasText: '다음' }).last();
  if (!(await waitForEnabled(page, footerNext))) {
    throw new Error('2단계 다음 버튼이 활성화되지 않았습니다');
  }
  await footerNext.click();
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
    // 주소 행 후보: 반드시 시/도 명칭으로 시작하는 짧은 텍스트 블록만 (안내 문구 배제)
    const texts = [...drawer.querySelectorAll('label, li, [class*=item], [class*=row], div')]
        .map((el) => (el.innerText || '').trim().replace(/\s+/g, ' '))
        .filter((text) => text.length >= 8 && text.length <= 120
            && /^(서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|충청|전북|전남|전라|경북|경남|경상|제주)/.test(text));
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
