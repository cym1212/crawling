package codeRecipe.crawling.coupang;

import codeRecipe.crawling.crawling.coupang.CoupangInboundPlanService;
import codeRecipe.crawling.crawling.coupang.CoupangInboundPlanService.InvoiceEntry;
import codeRecipe.crawling.crawling.coupang.CoupangInboundPlanService.NewItem;
import codeRecipe.crawling.crawling.domain.CoupangInboundPlan;
import codeRecipe.crawling.crawling.domain.CoupangInboundSetting;
import codeRecipe.crawling.crawling.domain.CoupangRestockSuggestion;
import codeRecipe.crawling.crawling.domain.InboundPlanStatus;
import codeRecipe.crawling.crawling.domain.RestockStatus;
import codeRecipe.crawling.crawling.repository.CoupangInboundInvoiceRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanItemRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundPlanRepository;
import codeRecipe.crawling.crawling.repository.CoupangInboundSettingRepository;
import codeRecipe.crawling.crawling.repository.CoupangInventoryRepository;
import codeRecipe.crawling.crawling.repository.CoupangRestockSuggestionRepository;
import codeRecipe.crawling.crawling.repository.CoupangWingAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoupangInboundPlanServiceTest {

    private CoupangInboundPlanRepository planRepository;
    private CoupangInboundPlanItemRepository itemRepository;
    private CoupangInboundInvoiceRepository invoiceRepository;
    private CoupangInboundSettingRepository settingRepository;
    private CoupangWingAddressRepository wingAddressRepository;
    private CoupangRestockSuggestionRepository suggestionRepository;
    private CoupangInventoryRepository inventoryRepository;
    private CoupangInboundPlanService service;

    @BeforeEach
    void setUp() {
        planRepository = mock(CoupangInboundPlanRepository.class);
        itemRepository = mock(CoupangInboundPlanItemRepository.class);
        invoiceRepository = mock(CoupangInboundInvoiceRepository.class);
        settingRepository = mock(CoupangInboundSettingRepository.class);
        wingAddressRepository = mock(CoupangWingAddressRepository.class);
        suggestionRepository = mock(CoupangRestockSuggestionRepository.class);
        inventoryRepository = mock(CoupangInventoryRepository.class);
        service = new CoupangInboundPlanService(planRepository, itemRepository, invoiceRepository,
                settingRepository, wingAddressRepository, suggestionRepository, inventoryRepository);
    }

    private void givenReturnAddressSet() {
        when(settingRepository.findById(CoupangInboundPlanService.SETTING_RETURN_ADDRESS))
                .thenReturn(Optional.of(CoupangInboundSetting.builder()
                        .settingKey(CoupangInboundPlanService.SETTING_RETURN_ADDRESS)
                        .settingValue("경기도 광주시 문형산길 246-9 B 01호")
                        .updatedAt(LocalDateTime.now())
                        .build()));
    }

    @Test
    void 회송지_미설정이면_계획_생성을_거부한다() {
        when(settingRepository.findById(any())).thenReturn(Optional.empty());

        assertThrows(CoupangInboundPlanService.ReturnAddressNotSetException.class,
                () -> service.createPlan(List.of(new NewItem(95296581965L, 10)), 1, "tester"));
    }

    @Test
    void 진행중_계획에_포함된_SKU는_중복_신청을_거부한다() {
        givenReturnAddressSet();
        when(itemRepository.findVendorItemIdsInPlans(anyCollection(), anyCollection()))
                .thenReturn(List.of(95296581965L));

        assertThrows(CoupangInboundPlanService.DuplicateInProgressException.class,
                () -> service.createPlan(List.of(new NewItem(95296581965L, 10)), 1, "tester"));
    }

    @Test
    void 계획_생성시_해당_SKU의_제안이_REQUESTED로_전환된다() {
        givenReturnAddressSet();
        when(itemRepository.findVendorItemIdsInPlans(anyCollection(), anyCollection())).thenReturn(List.of());
        when(inventoryRepository.findByVendorItemId(any())).thenReturn(Optional.empty());
        when(planRepository.save(any())).thenAnswer(invocation -> {
            CoupangInboundPlan plan = invocation.getArgument(0);
            return plan.toBuilder().planId(77L).build();
        });
        CoupangRestockSuggestion suggestion = CoupangRestockSuggestion.builder()
                .vendorItemId(95296581965L)
                .currentQuantity(3)
                .dailyAvgSales(new BigDecimal("1.00"))
                .suggestedQuantity(18)
                .status(RestockStatus.SUGGESTED)
                .suggestionDate(LocalDate.now())
                .createdAt(LocalDateTime.now())
                .build();
        when(suggestionRepository.findByVendorItemIdInAndStatus(anyCollection(), any()))
                .thenReturn(List.of(suggestion));

        Long planId = service.createPlan(List.of(new NewItem(95296581965L, 10)), 2, "tester");

        assertEquals(77L, planId);
        ArgumentCaptor<CoupangRestockSuggestion> captor = ArgumentCaptor.forClass(CoupangRestockSuggestion.class);
        verify(suggestionRepository).save(captor.capture());
        assertEquals(RestockStatus.REQUESTED, captor.getValue().getStatus());
    }

    @Test
    void 제출_전_상태에서는_송장_접수를_거부한다() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan(InboundPlanStatus.REQUESTED, 1)));

        assertThrows(IllegalStateException.class,
                () -> service.registerInvoices(1L, List.of(new InvoiceEntry(1, "12345678901"))));
    }

    @Test
    void 송장_수가_박스_수와_다르면_거부한다() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan(InboundPlanStatus.SUBMITTED, 2)));

        assertThrows(IllegalArgumentException.class,
                () -> service.registerInvoices(1L, List.of(new InvoiceEntry(1, "12345678901"))));
    }

    @Test
    void 송장_접수시_INVOICE_ISSUED로_전환된다() {
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan(InboundPlanStatus.SUBMITTED, 2)));
        when(invoiceRepository.findByPlanIdAndBoxNo(any(), any())).thenReturn(Optional.empty());

        InboundPlanStatus status = service.registerInvoices(1L,
                List.of(new InvoiceEntry(1, "12345678901"), new InvoiceEntry(2, "12345678902")));

        assertEquals(InboundPlanStatus.INVOICE_ISSUED, status);
        ArgumentCaptor<CoupangInboundPlan> captor = ArgumentCaptor.forClass(CoupangInboundPlan.class);
        verify(planRepository).save(captor.capture());
        assertEquals(InboundPlanStatus.INVOICE_ISSUED, captor.getValue().getStatus());
    }

    @Test
    void WING_목록에_없는_회송지는_저장을_거부한다() {
        when(wingAddressRepository.findByAddressText("아무 주소")).thenReturn(Optional.empty());

        assertThrows(CoupangInboundPlanService.AddressNotInWingException.class,
                () -> service.saveReturnAddress("아무 주소"));
    }

    private CoupangInboundPlan plan(InboundPlanStatus status, int boxCount) {
        return CoupangInboundPlan.builder()
                .planId(1L)
                .status(status)
                .boxCount(boxCount)
                .requestedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
